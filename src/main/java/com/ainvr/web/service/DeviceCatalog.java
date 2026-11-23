package com.ainvr.web.service;

import com.ainvr.cloud.DeviceDiscovery;
import com.ainvr.cloud.MiCloudApi;
import com.ainvr.cloud.MiCloudUnauthorizedException;
import com.ainvr.hub.StreamHub;
import com.ainvr.miss.DualLens;
import com.ainvr.model.CameraDevice;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.text.Collator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 当前账号下摄像头目录（Web 单例）。登录/手动刷新时从米家云拉取并缓存，
 * 供 RTSP / HLS / 录像按 DID 解析设备（model、局域网 IP、在线状态）。
 *
 * <p>v1.1.9：云端列表刷新后识别「离线→在线」和「局域网 IP 变化」并唤醒对应取流，
 * 避免正在预览的窗口还卡在最长 5 分钟的熔断退避里。</p>
 *
 * <p>v1.2.9：在「离线→在线」「在线→离线」两种跃变处各落一条事件（类型 reconnect / offline），
 * 便于在事件告警页集中看到设备连接状态变化，无需逐一翻看预览窗口。事件仅在实际状态跃变时
 * 产生（首屏或新绑定设备 was==null 的情形跳过），不会在进程启动或列表首次拉取时刷屏。</p>
 */
@Component
public class DeviceCatalog {

    private static final Logger log = LoggerFactory.getLogger(DeviceCatalog.class);

    /** 后台自动刷新间隔（秒）：用于让在线/离线状态自动更新，不必等手动刷新。 */
    private static final long AUTO_REFRESH_SECONDS = 60;

    private final SessionHolder sessionHolder;
    private final StreamHub streamHub;
    private final EventStore events;
    private final AppSettingsStore settings;
    private volatile List<CameraDevice> devices = List.of();
    private final ScheduledExecutorService scheduler;

    public DeviceCatalog(SessionHolder sessionHolder, StreamHub streamHub, EventStore events,
                         AppSettingsStore settings) {
        this.sessionHolder = sessionHolder;
        this.streamHub = streamHub;
        this.events = events;
        this.settings = settings;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "device-catalog-refresh");
            t.setDaemon(true);
            return t;
        });
        // 启动预热：3s 后若已登录（含恢复的会话）立即拉一次设备列表——否则进程重启后
        // devices 一直为空，用户打开页面还要再等一次米家云端全量刷新（数秒）才能出卡片。
        this.scheduler.schedule(this::safeRefresh, 3, TimeUnit.SECONDS);
        this.scheduler.scheduleAtFixedRate(this::safeRefresh,
                AUTO_REFRESH_SECONDS, AUTO_REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    /** 后台任务：仅在已登录时刷新，任何异常都静默保留上一份缓存。 */
    private void safeRefresh() {
        try {
            // v2.3.1：空列表也刷新（启动预热场景）；未登录时跳过
            if (sessionHolder.isLoggedIn()) {
                refresh();
            }
        } catch (Exception e) {
            log.debug("后台刷新设备列表失败，保留旧缓存：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 强制从云端刷新设备列表；401 时自动续期并重试一次。 */
    public List<CameraDevice> refresh() {
        var session = sessionHolder.require();
        List<CameraDevice> previous = devices;
        List<CameraDevice> fresh;
        try {
            fresh = new DeviceDiscovery(new MiCloudApi(session)).listCameras();
        } catch (MiCloudUnauthorizedException e) {
            log.info("设备列表会话过期，自动续期后重试");
            var renewed = sessionHolder.renew();
            fresh = new DeviceDiscovery(new MiCloudApi(renewed)).listCameras();
        }
        // v2.3.1 热修：落盘前统一排序——此前仅 all() 读侧排序，refresh=true 的调用方
        // （解锁进页 / 手动刷新 / 休眠下发后的 loadDevices(true)）拿到的是发现顺序，
        // 云端每次返回顺序不稳定 → 前端卡片顺序来回跳
        devices = sortedSnapshot(expandDualLens(fresh));
        notifyTransitions(previous, fresh);
        return devices;
    }

    /** 设备展示顺序：手动排序（deviceOrder）优先，其次中文名 Collator + did，确定且稳定。 */
    private List<CameraDevice> sortedSnapshot(List<CameraDevice> list) {
        if (list.size() < 2) {
            return list;
        }
        Map<String, Integer> order = settings.deviceOrder();
        if (order.isEmpty()) {
            Collator collator = Collator.getInstance(Locale.CHINA);
            return list.stream()
                    .sorted(Comparator.comparing(CameraDevice::name, collator)
                            .thenComparing(CameraDevice::did))
                    .toList();
        }
        return list.stream()
                .sorted(Comparator
                        .comparingInt((CameraDevice c) ->
                                order.getOrDefault(DualLens.baseDid(c.did()), Integer.MAX_VALUE))
                        .thenComparingInt(c -> c.did().endsWith(DualLens.SUFFIX) ? 1 : 0)
                        .thenComparing(CameraDevice::did))
                .toList();
    }

    /**
     * 双摄机型展开：为启用第二镜头的设备追加一条 DID 后缀为 {@code #2} 的虚拟摄像头条目，
     * 名字追加「（副镜头）」。所有下游（预览 / 录像 / 回放 / 快照 / 事件）把它当普通摄像头，
     * 独立取流、独立录像分段、独立事件；设备在线状态跟随真实设备。
     */
    private List<CameraDevice> expandDualLens(List<CameraDevice> real) {
        List<CameraDevice> out = new ArrayList<>(real);
        for (CameraDevice c : real) {
            Boolean override = settings.dualLensOverride(c.did());
            boolean enabled = override != null ? override : DualLens.knownDualLensModel(c.model());
            if (enabled) {
                out.add(new CameraDevice(DualLens.secondaryDid(c.did()),
                        c.name() + "（副镜头）", c.model(), c.mac(), c.localIp(), c.online()));
            }
        }
        return out;
    }

    /** 云端状态的两种关键跃变，都要求立即丢弃旧会话重新协商。 */
    private void notifyTransitions(List<CameraDevice> before, List<CameraDevice> after) {
        Map<String, CameraDevice> old = new HashMap<>();
        for (CameraDevice c : before) {
            old.put(c.did(), c);
        }
        for (CameraDevice now : after) {
            CameraDevice was = old.get(now.did());
            if (was == null) {
                // 首次出现 / 新绑定设备：本来就没有上游会话，无需唤醒
                continue;
            }
            if (!was.online() && now.online()) {
                log.info("设备 {} 由离线转为在线，立即唤醒取流", now.did());
                streamHub.wakeup(now.did(), "米家云：离线→在线", true);
                // 双摄副镜头虚拟流一并唤醒（无活跃订阅时 wakeup 返回 false，无副作用）
                streamHub.wakeup(DualLens.secondaryDid(now.did()), "米家云：离线→在线", true);
                // 重连事件：集中呈现在事件告警页，无需逐一翻看预览窗口
                events.add(now.did(), now.name(), "reconnect", "info", "设备重连上线", 0.0, null, List.of());
            } else if (was.online() && !now.online()) {
                log.info("设备 {} 由在线转为离线", now.did());
                events.add(now.did(), now.name(), "offline", "warn", "设备离线", 0.0, null, List.of());
            } else if (!Objects.equals(was.localIp(), now.localIp())
                    && now.localIp() != null && !now.localIp().isBlank()) {
                log.info("设备 {} 局域网 IP 由 {} 变为 {}，立即唤醒取流",
                        now.did(), was.localIp(), now.localIp());
                streamHub.wakeup(now.did(), "云端局域网 IP 变化", true);
                streamHub.wakeup(DualLens.secondaryDid(now.did()), "云端局域网 IP 变化", true);
            }
        }
    }

    /**
     * 当前设备列表。v1.2.21 起固定按名称排序（中文拼音 Collator，同名按 did 兜底），
     * 米家云端返回顺序在两次刷新间不保证稳定，此前会导致预览宫格 / 回放设备下拉
     * 的 tab 顺序漂移而点错设备。v2.3.1：用户在设置页手动排序后（settings 的
     * {@code deviceOrder}），按编号升序固定，副镜头紧跟主镜头；未编号设备按发现顺序排最后。
     */
    public List<CameraDevice> all() {
        List<CameraDevice> snapshot = devices;
        if (snapshot.size() < 2) {
            return snapshot;
        }
        return sortedSnapshot(snapshot);
    }

    public Optional<CameraDevice> find(String did) {
        return devices.stream().filter(c -> c.did().equals(did)).findFirst();
    }

    /** 找到则返回，否则刷新一次再找（处理刚登录尚未拉列表的情况）。 */
    public CameraDevice findOrRefresh(String did) {
        return find(did).orElseGet(() ->
                refresh().stream().filter(c -> c.did().equals(did)).findFirst().orElse(null));
    }
}
