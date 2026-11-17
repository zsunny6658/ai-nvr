package com.ainvr.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.DataDir;
import com.ainvr.store.EventStore;
import com.ainvr.store.MotionSettings;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 运动触发自动录像状态机（v1.2.8，默认关闭）。
 *
 * <p><b>与手动录像的关系</b>：手动录像写入 {@code recordings.json} 作为"期望持续录像"，
 * 由 {@link RecordingService#reconcile()} 在重启后自动恢复。自动录像绝不能走那条路径，
 * 否则一次运动就会留下永久标记、服务重启后无限录下去。因此这里统一调用
 * {@link RecordingService#startTransient} / {@link RecordingService#stopTransient}，
 * 它们只建立/解除订阅，不碰持续录像状态。</p>
 *
 * <p><b>状态机</b>：每轮读取 {@link MotionDetector#lastMotionAtMs} 拿到"画面最近确实在变化"
 * 的时间；变化窗口内且该设备未在录 → 开录；连续 {@code recordTailSec} 秒无变化 → 封段停录。
 * 云台静默期与低照度帧不更新该时间，因此机械转动不会拉长录制。事件冷却期不影响开录判断，
 * 避免 30 秒冷却把一段持续运动切成两半。</p>
 *
 * <p><b>不抢占用户</b>：已在录（含手动录像）的设备一律不介入；若用户显式停止了本服务
 * 开启的临时录像，会记一个冷却窗口，期间不自动重开；关闭运动侦测或自动录像开关时，
 * 立刻封段停掉本服务开启的所有会话。</p>
 *
 * <p><b>重启恢复</b>：内存中的 owned 集合每次变化都落盘到 {@code .invr/motion-recordings.json}
 * （仅 DID 列表）。进程重启后读取该列表，对这些设备尝试恢复一次临时录像并给一个尾延时
 * 窗口；若此后没有新的运动，窗口到期即自动停录。因此既不会丢掉断电时正在写的那段，
 * 也<b>不会</b>变成常开录像。</p>
 */
@Service
public class MotionRecordingService {

    private static final Logger log = LoggerFactory.getLogger(MotionRecordingService.class);
    private static final String STATE_FILE = "motion-recordings.json";
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final RecordingService recordings;
    private final MotionDetector detector;
    private final AppSettingsStore settingsStore;
    private final EventStore events;
    private final Clock clock;
    private final Path stateFile;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 本服务当前持有的临时录像 DID；只有这里的会话会被本服务停止。 */
    private final Set<String> owned = ConcurrentHashMap.newKeySet();
    /** 每台设备最近一次观测到的运动时间（取 detector 与本地恢复值的较大者）。 */
    private final Map<String, Long> lastMotionAt = new ConcurrentHashMap<>();
    /** 用户手动停止后的抑制窗口，避免状态机立刻和用户对着拉起录像。 */
    private final Map<String, Long> manualStopUntil = new ConcurrentHashMap<>();
    /** 重启时从磁盘恢复出来、等待首轮尝试重建的 DID。 */
    private final Set<String> pendingRecovery = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "invr-motion-recorder");
                t.setDaemon(true);
                return t;
            });

    @Autowired
    public MotionRecordingService(RecordingService recordings, MotionDetector detector,
                                  AppSettingsStore settingsStore, EventStore events) {
        this(recordings, detector, settingsStore, events,
                Clock.systemUTC(), DataDir.get().resolve(STATE_FILE));
    }

    /** 测试可注入可控时钟与临时状态文件。 */
    MotionRecordingService(RecordingService recordings, MotionDetector detector,
                           AppSettingsStore settingsStore, EventStore events,
                           Clock clock, Path stateFile) {
        this.recordings = recordings;
        this.detector = detector;
        this.settingsStore = settingsStore;
        this.events = events;
        this.clock = clock;
        this.stateFile = stateFile;
        load();
    }

    /** 每秒巡检一次；实际节流由侦测器的 intervalSec 与尾延时决定，这里只做轻量判定。 */
    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::safeTick, 2000, 1000, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            log.warn("自动录像巡检异常：{}", t.toString());
        }
    }

    /** 一轮状态机；包可见，便于单测直接驱动而不依赖定时器。 */
    synchronized void tick() {
        MotionSettings cfg = settingsStore.motion();
        long now = clock.millis();
        boolean changed = false;

        // 重启恢复：给每个崩溃时正在录的设备一个尾延时窗口，没有新运动则自动收敛停录
        if (!pendingRecovery.isEmpty()) {
            for (String did : pendingRecovery) {
                lastMotionAt.put(did, now);
            }
            pendingRecovery.clear();
        }

        // 汇总侦测器观测到的运动时间（取较大值；detector 的时钟可能比本服务更"新"）
        for (String did : detector.motionDids()) {
            long t = detector.lastMotionAtMs(did);
            if (t > 0) {
                lastMotionAt.merge(did, t, Math::max);
            }
        }

        if (!cfg.enabled() || !cfg.autoRecord()) {
            for (String did : new ArrayList<>(owned)) {
                changed |= stopOwned(did, "运动侦测或自动录像已关闭");
            }
            if (changed) {
                persist();
            }
            return;
        }

        // 1) 到期停录 / 感知用户手动停止
        for (String did : new ArrayList<>(owned)) {
            if (!recordings.isRecording(did)) {
                // 会话被外部（用户点"停止录像"、切目录重连失败等）结束：尊重用户，抑制一段时间
                owned.remove(did);
                manualStopUntil.put(did, now + cfg.cooldownMs());
                changed = true;
                log.info("[{}] 临时录像已被外部停止，{} 秒内不自动重开", did, cfg.cooldownSec());
                continue;
            }
            long last = lastMotionAt.getOrDefault(did, 0L);
            if (now - last >= cfg.recordTailMs()) {
                changed |= stopOwned(did, "画面已静止 " + cfg.recordTailSec() + " 秒");
            }
        }

        // 2) 变化窗口内开录
        for (Map.Entry<String, Long> e : lastMotionAt.entrySet()) {
            String did = e.getKey();
            if (!cfg.covers(did)) {
                continue;
            }
            if (now - e.getValue() >= cfg.recordTailMs()) {
                continue;
            }
            if (owned.contains(did) || recordings.isRecording(did)
                    || recordings.isPersistentRecording(did)) {
                continue;
            }
            if (now < manualStopUntil.getOrDefault(did, 0L)) {
                continue;
            }
            try {
                recordings.startTransient(did, null);
                owned.add(did);
                changed = true;
                Map<String, Object> detection = new LinkedHashMap<>();
                detection.put("label", "autoRecord");
                detection.put("did", did);
                detection.put("tailSec", cfg.recordTailSec());
                events.add(did, detector.deviceName(did), "record", "info",
                        "运动触发自动录像", 0, null, List.of(detection));
                log.info("[{}] 运动触发自动录像开始（静止 {} 秒后自动停录）", did, cfg.recordTailSec());
            } catch (Exception ex) {
                // 设备离线 / 未登录米家 / 取流未就绪：不刷屏，下一轮继续重试
                log.debug("[{}] 自动录像暂未开始：{}", did, ex.toString());
            }
        }

        if (changed) {
            persist();
        }
    }

    private boolean stopOwned(String did, String reason) {
        try {
            recordings.stopTransient(did);
            log.info("[{}] 自动录像结束：{}", did, reason);
        } catch (Exception e) {
            log.debug("[{}] 自动录像结束异常：{}", did, e.toString());
        }
        return owned.remove(did);
    }

    /** 运行态快照，供 /api/motion/recording 观测与排障。 */
    public synchronized Map<String, Object> status() {
        MotionSettings cfg = settingsStore.motion();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled());
        m.put("autoRecord", cfg.autoRecord());
        m.put("recordTailSec", cfg.recordTailSec());
        m.put("owned", new ArrayList<>(owned));
        m.put("lastMotionAt", new LinkedHashMap<>(lastMotionAt));
        m.put("manualStopUntil", new LinkedHashMap<>(manualStopUntil));
        m.put("now", clock.millis());
        return m;
    }

    private void load() {
        if (!Files.exists(stateFile)) {
            return;
        }
        try {
            List<String> dids = mapper.readValue(Files.readAllBytes(stateFile), LIST_TYPE);
            if (dids != null) {
                for (String d : dids) {
                    if (d != null && !d.isBlank()) {
                        pendingRecovery.add(d.trim());
                    }
                }
            }
            if (!pendingRecovery.isEmpty()) {
                log.info("检测到上次运行未结束的自动录像，将按尾延时窗口恢复：{}", pendingRecovery);
            }
        } catch (IOException e) {
            log.warn("自动录像状态读取失败，按无待恢复会话处理：{}", e.getMessage());
        }
    }

    /** 只持久化 DID 列表，临时文件 + 原子 rename，写失败不阻断录像。 */
    private synchronized void persist() {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = stateFile.resolveSibling(STATE_FILE + ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(new ArrayList<>(owned)));
            try {
                Files.move(tmp, stateFile, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("自动录像状态保存失败：{}", e.getMessage());
        }
    }
}
