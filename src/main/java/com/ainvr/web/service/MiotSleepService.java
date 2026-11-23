package com.ainvr.web.service;

import com.ainvr.cloud.LoginSession;
import com.ainvr.cloud.MiCloudApi;
import com.ainvr.cloud.MiCloudUnauthorizedException;
import com.ainvr.miss.DualLens;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 摄像头休眠状态探测（v2.3.1）：米家云 MIoT spec 的「相机开关」属性。
 *
 * <p>背景：休眠是米家摄像头的常见状态（定时休眠 / 手动休眠），但
 * {@code isOnline} 云端心跳不受休眠影响（休眠中仍报在线），CS2 局域网探测
 * 也会因休眠期间唤醒监听仍在监听 LAN_SEARCH 而误判可达——两者都无法区分
 * 「休眠」与「正常在线」。</p>
 *
 * <p>米家 App 的休眠开关在 MIoT spec 中即 Camera Control 服务的 {@code on}
 * 属性（{@code siid=2, piid=1}，bool）：true=工作中，false=休眠中。本服务每
 * {@value #INTERVAL_SEC} 秒对全部设备做一次批量 {@code /miotspec/prop/get}
 * （一次请求带所有设备的参数，云端开销与摄像头数量无关），缓存最近一次结果。
 * 属性可写，同时提供 {@link #setSleep} 供界面直接唤醒 / 休眠摄像头。</p>
 *
 * <p>siid/piid 随固件可能变化，可用系统属性覆盖：{@code -Dainvr.sleep.siid=}、
 * {@code -Dainvr.sleep.piid=}。固件不支持该属性时设备结果保持 null，界面按在线显示。</p>
 */
@Component
public class MiotSleepService {

    private static final Logger log = LoggerFactory.getLogger(MiotSleepService.class);

    private static final int INTERVAL_SEC = 90;
    /** /miotspec/prop/* 的成功业务码；非 0（如 -4006 固件不支持）按未知处理。 */
    private static final int CODE_OK = 0;

    private final DeviceCatalog catalog;
    private final SessionHolder sessionHolder;
    private final com.ainvr.hub.StreamHub streamHub;
    /** 主设备 did → 是否休眠（true=休眠中）。缺省表示尚未探测或固件不支持。 */
    private final ConcurrentHashMap<String, Boolean> sleepState = new ConcurrentHashMap<>();
    /** 主设备 did → 最近一次属性读取失败码（成功后移除）。用于失败可见化，避免静默降级成「一直在线」。 */
    private final ConcurrentHashMap<String, Integer> failCodes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public MiotSleepService(DeviceCatalog catalog, SessionHolder sessionHolder,
                            com.ainvr.hub.StreamHub streamHub) {
        this.catalog = catalog;
        this.sessionHolder = sessionHolder;
        this.streamHub = streamHub;
        // v2.3.1 休眠闸门：休眠中的摄像头不拨上游流（否则会被局域网可达性探测的
        // wakeup 不断清零退避，形成 15 秒一轮的重连风暴）
        streamHub.setSleepGate(this::sleeping);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "miot-sleep-probe");
            t.setDaemon(true);
            return t;
        });
        // 启动 15 秒后首探（等设备目录就绪与米家会话建立），之后每 90 秒一次
        this.scheduler.scheduleAtFixedRate(this::safeProbe, 15, INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void safeProbe() {
        try {
            probeAll();
        } catch (MiCloudUnauthorizedException e) {
            log.debug("休眠探测米家会话过期：{}", e.getMessage());
        } catch (Exception e) {
            log.debug("休眠探测异常（忽略）：{}", e.toString());
        }
    }

    /**
     * 逐设备读全部主设备的休眠属性。
     *
     * <p>⚠️ 不做跨 did 批量：米家云 {@code /miotspec/prop/get} 对「一次请求携带多个设备
     * 参数」的兼容性不可靠（多台真机上批量探测持续失败、休眠一直显示在线），而单设备
     * 单请求已被预置位功能（MiotPresetService）真机验证有效。设备数一般 ≤4，每 90s
     * 一轮、每轮每设备一个请求，云端开销可忽略。单设备失败不影响其他设备。</p>
     */
    private void probeAll() {
        List<String> dids = catalog.all().stream()
                // 虚拟副镜头（did#2）不是真实云端设备，跳过；其休眠状态跟随主镜头
                .filter(d -> !DualLens.isSecondary(d.did()))
                .map(d -> d.did())
                .toList();
        for (String did : dids) {
            try {
                probeOne(did);
            } catch (MiCloudUnauthorizedException e) {
                throw e; // 交给 safeProbe 统一处理会话过期
            } catch (Exception e) {
                log.debug("[{}] 休眠探测单设备异常（忽略）：{}", did, e.toString());
            }
        }
    }

    /** 立即异步触发一轮全量探测（设备列表强制刷新后调用，保证解锁/刷新后休眠徽标新鲜）。 */
    public void kick() {
        scheduler.execute(this::safeProbe);
    }

    /**
     * 实时单设备探测（GET /api/devices/{did}/sleep 验证用）：绕过 90s 轮询缓存直接读云端属性，
     * 返回 {did, code, value(可空), sleeping(可空=null 时回退缓存值)}。成功结果同步进缓存。
     */
    public Map<String, Object> probeOne(String did) {
        String base = DualLens.baseDid(did);
        Map<String, Object> param = Map.of(
                "did", base,
                "siid", propInt("ainvr.sleep.siid", 2),
                "piid", propInt("ainvr.sleep.piid", 1));
        JsonNode resp = call("/miotspec/prop/get", Map.of("params", List.of(param)));
        JsonNode item = resp.path("result").path(0);
        int code = item.path("code").asInt(-1);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("did", base);
        out.put("code", code);
        if (code == CODE_OK && item.path("value").isBoolean()) {
            boolean value = item.path("value").asBoolean();
            boolean sleeping = !value;
            failCodes.remove(base);
            Boolean prev = sleepState.put(base, sleeping);
            out.put("value", value);
            out.put("sleeping", sleeping);
            // v2.3.1 成功探测也可见化：状态变化（含首次探明）打 INFO，真机日志可自证探测在跑
            if (prev == null || prev != sleeping) {
                log.info("[{}] 休眠探测：摄像头{}（MIoT on={}）{}", base,
                        sleeping ? "进入休眠，已暂停取流" : "工作中",
                        value, prev == null ? "（首次探明）" : "（状态变化）");
                if (prev != null && !sleeping) {
                    // 休眠→工作：立即唤醒取流（打断闸门挂起与退避等待）
                    streamHub.wakeup(base, "休眠解除", true);
                }
            }
        } else {
            Integer fc = failCodes.put(base, code);
            if (fc == null || fc != code) {
                // 失败时带原始响应（截断）：result 为空（code=-1）时能看出云端到底回了什么
                String raw = resp.toString();
                if (raw.length() > 300) {
                    raw = raw.substring(0, 300) + "…";
                }
                log.info("[{}] 休眠实时探测失败 code={} resp={}", base, code, raw);
            }
            out.put("value", null);
            out.put("sleeping", sleepState.get(base)); // 探测失败回退最近缓存值（可能为 null）
        }
        return out;
    }

    /** 该设备是否休眠：true=休眠中，false=工作中；null=尚未探测/不支持。副镜头跟随主镜头。 */
    public Boolean sleeping(String did) {
        return sleepState.get(DualLens.baseDid(did));
    }

    /** 最近一次属性读取失败码；null=最近一次成功或尚未探测（供界面自证探测状态）。 */
    public Integer probeFailCode(String did) {
        return failCodes.get(DualLens.baseDid(did));
    }

    /** 设置休眠状态（sleep=true 休眠 / false 唤醒），米家会话过期自动续期重试一次。 */
    public void setSleep(String did, boolean sleep) {
        String base = DualLens.baseDid(did);
        Map<String, Object> param = Map.of(
                "did", base,
                "siid", propInt("ainvr.sleep.siid", 2),
                "piid", propInt("ainvr.sleep.piid", 1),
                "value", !sleep);
        try {
            call("/miotspec/prop/set", Map.of("params", List.of(param)));
        } catch (MiCloudUnauthorizedException e) {
            log.info("[{}] 设置休眠米家会话过期，续期后重试", base);
            LoginSession session = sessionHolder.renew();
            new MiCloudApi(session).call("/miotspec/prop/set", Map.of("params", List.of(param)));
        }
        Boolean prev = sleepState.put(base, sleep);
        if (prev == null || prev != sleep) {
            log.info("[{}] 已{}摄像头（MIoT {}.{}）", base, sleep ? "休眠" : "唤醒",
                    propInt("ainvr.sleep.siid", 2), propInt("ainvr.sleep.piid", 1));
            if (!sleep) {
                // 手动唤醒：立即恢复取流（打断休眠闸门挂起）
                streamHub.wakeup(base, "手动唤醒", true);
            }
        }
    }

    private JsonNode call(String api, Object params) {
        return new MiCloudApi(sessionHolder.require()).call(api, params);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static int propInt(String key, int def) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
