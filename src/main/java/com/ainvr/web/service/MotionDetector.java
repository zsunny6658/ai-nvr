package com.ainvr.web.service;

import com.ainvr.codec.FrameQuality;
import com.ainvr.model.CameraDevice;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import com.ainvr.store.MotionSettings;
import com.ainvr.store.MotionZone;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 运动侦测巡检：复用「兼容画面」已有的常驻 JPEG 帧做 8×8 感知哈希帧差。
 *
 * <p><b>为什么不新起抓图进程</b>：{@link LiveFrameService} 已按 DID 维持一条上游订阅与一个
 * 常驻 HEVC 解码器（≤4fps、宽 ≤640），直接取缓存帧即可，既不会额外占用小米摄像头本来就
 * 有限的取流通道，也不需要外部 ffprobe / ffmpeg。本类默认关闭，关闭时不取帧，超过帧服务的
 * 空闲时间后订阅会自动回收，零额外开销。</p>
 *
 * <p><b>误报治理</b>（比单次帧差更保守）：① 感知哈希与稳定基准帧比较，汉明距离/64 作为
 * 变化率（低于阈值时基准跟随当前帧，因此缓慢光照漂移不会累积误报）；
 * ② 连续 {@code confirmFrames} 次超阈值才判定；③ 触发后进入 {@code cooldownSec} 冷却；
 * ④ 云台动作后 {@code silentSec} 内静默并重置基准（转动必然导致整幅画面剧变）；
 * ⑤ 平均亮度过低的帧跳过，避免夜间红外切换 / 全黑画面刷屏；⑥ 阈值可在运行时调整，
 * 配合 {@code /api/motion/status} 的 lastScore 观察实际分布后再定值。</p>
 *
 * <p>参数每轮重新读取，修改设置后无需重启；巡检单线程串行，避免多路同时软解抢占 CPU。</p>
 */
@Service
public class MotionDetector {

    private static final Logger log = LoggerFactory.getLogger(MotionDetector.class);

    /** 平均亮度低于此值视为无效帧（近乎全黑），跳过判定。 */
    static final int MIN_LUMA = 18;
    /**
     * 8×8 灰度感知哈希的亮度标准差低于此值，视为“无信号 / 纯色帧”（摄像头断流常呈均匀灰屏）。
     * 此类帧几乎全屏同色，与真实画面汉明距离极大，若参与帧差会被误判为“画面变动”而触发告警；
     * 且 real→gray 与 gray→real 的跳变都会被判成运动。故整段无信号期间不触发，信号恢复后的
     * 首帧当作新基准（不触发），避免灰屏被误报为事件。
     */
    static final int NOSIGNAL_STD = 6;
    private static final int HASH_BITS = 64;

    private final LiveFrameService frames;
    private final DeviceCatalog catalog;
    private final AppSettingsStore settingsStore;
    private final EventStore events;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "invr-motion-detector");
                t.setDaemon(true);
                return t;
            });
    private volatile long lastTickSampleMs = 0;

    @Autowired
    public MotionDetector(LiveFrameService frames, DeviceCatalog catalog,
                          AppSettingsStore settingsStore, EventStore events) {
        this(frames, catalog, settingsStore, events, Clock.systemUTC());
    }

    /** 测试可注入可控时钟；Spring 使用上面的四参构造。 */
    MotionDetector(LiveFrameService frames, DeviceCatalog catalog,
                   AppSettingsStore settingsStore, EventStore events, Clock clock) {
        this.frames = frames;
        this.catalog = catalog;
        this.settingsStore = settingsStore;
        this.events = events;
        this.clock = clock;
    }

    /** 每秒唤醒一次，实际采样间隔由运行时的 intervalSec 决定，改设置即时生效。 */
    @PostConstruct
    public void start() {
        scheduler.scheduleWithFixedDelay(this::safeTick, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void safeTick() {
        try {
            tick();
        } catch (Throwable t) {
            log.warn("运动侦测巡检异常：{}", t.toString());
        }
    }

    /** 一轮巡检；包可见，便于单测直接驱动而不依赖定时器。 */
    void tick() {
        MotionSettings cfg = settingsStore.motion();
        long now = clock.millis();
        if (!cfg.enabled()) {
            return; // 不取帧：帧服务空闲后自行回收订阅
        }
        if (!cfg.inActiveWindow(now)) {
            return; // v1.2.21 侦测时段计划：时段外不采样不告警（帧服务空闲后自动回收订阅）
        }
        if (now - lastTickSampleMs < cfg.intervalSec() * 1000L) {
            return;
        }
        lastTickSampleMs = now;
        events.purge(cfg.maxEvents(), cfg.retentionDays());

        List<CameraDevice> targets = new ArrayList<>();
        try {
            for (CameraDevice d : catalog.all()) {
                if (d.online() && cfg.covers(d.did())) {
                    targets.add(d);
                }
            }
        } catch (Throwable t) {
            log.debug("设备目录读取失败，本轮跳过巡检：{}", t.toString());
            return;
        }
        // v1.2.19：每轮一次性读取排除区配置，逐设备映射为 8×8 哈希掩码（避免逐设备重复读文件）
        Map<String, List<MotionZone>> zones;
        try {
            zones = settingsStore.motionExcludeZones();
        } catch (Throwable t) {
            zones = Map.of();
        }
        for (CameraDevice d : targets) {
            sampleOne(d.did(), cfg, now, MotionZone.mask64(zones.get(d.did())));
        }
    }

    /** 手动采样一台设备（真实参与状态机，可能落一条事件），用于调阈值时观察即时评分。 */
    public SampleResult sample(String did) {
        MotionSettings cfg = settingsStore.motion();
        List<MotionZone> zones;
        try {
            zones = settingsStore.excludeZonesFor(did);
        } catch (Throwable t) {
            zones = List.of();
        }
        return sampleOne(did, cfg, clock.millis(), MotionZone.mask64(zones));
    }

    /** 云台动作后静默一段时间并重置帧差基准，避免转动过程被判成运动事件。 */
    public void noteMotor(String did) {
        MotionSettings cfg = settingsStore.motion();
        State s = states.computeIfAbsent(did, k -> new State());
        s.silentUntilMs = clock.millis() + cfg.silentMs();
        s.hasBaseline = false; // 由下一帧重建基准
        s.confirm = 0;
    }

    /** 运行状态快照：配置、逐设备评分分布与事件统计，供误报率评估与排障。 */
    /**
     * 某设备最近一次「画面确实在变化」的时间戳（epoch ms，从未运动过返回 0）。
     * 供 {@code MotionRecordingService} 判断自动录像是否该停；静默期与低照度帧不更新。
     */
    public long lastMotionAtMs(String did) {
        State s = states.get(did);
        return s == null ? 0L : s.lastMotionAtMs;
    }

    /** 已被巡检过的设备 DID 快照；状态机据此遍历候选设备，不触发任何取流。 */
    public Set<String> motionDids() {
        return new HashSet<>(states.keySet());
    }

    public Map<String, Object> status() {
        MotionSettings cfg = settingsStore.motion();
        long now = clock.millis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled());
        m.put("threshold", cfg.threshold());
        m.put("intervalSec", cfg.intervalSec());
        m.put("cooldownSec", cfg.cooldownSec());
        m.put("confirmFrames", cfg.confirmFrames());
        m.put("silentSec", cfg.silentSec());
        m.put("retentionDays", cfg.retentionDays());
        m.put("maxEvents", cfg.maxEvents());
        m.put("dids", cfg.dids());
        m.put("autoRecord", cfg.autoRecord());
        m.put("recordTailSec", cfg.recordTailSec());
        m.put("activeFrameSources", frames.activeSources());
        m.put("events", events.stats());

        Map<String, Object> perDevice = new LinkedHashMap<>();
        for (Map.Entry<String, State> e : states.entrySet()) {
            State s = e.getValue();
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("lastScore", round3(s.lastScore));
            one.put("lastLuma", s.lastLuma);
            one.put("confirm", s.confirm);
            one.put("samples", s.samples);
            one.put("triggered", s.triggered);
            one.put("lastSampleAt", s.lastSampleAtMs);
            one.put("lastEventAt", s.lastEventAtMs);
            one.put("lastMotionAt", s.lastMotionAtMs);
            one.put("excludedBlocks", s.excludedBlocks);
            one.put("cooldownRemainingSec", remainingSec(s.lastEventAtMs + cfg.cooldownMs(), now));
            one.put("silentRemainingSec", remainingSec(s.silentUntilMs, now));
            one.put("lastError", s.lastError);
            perDevice.put(e.getKey(), one);
        }
        m.put("devices", perDevice);
        return m;
    }

    // ===================== 判定核心 =====================

    private SampleResult sampleOne(String did, MotionSettings cfg, long now, long mask) {
        State s = states.computeIfAbsent(did, k -> new State());
        s.excludedBlocks = 64 - Long.bitCount(mask);
        byte[] jpeg;
        try {
            jpeg = frames.frame(did);
        } catch (Throwable t) {
            s.lastError = "取帧失败：" + t.getMessage();
            return new SampleResult(did, 0, cfg.threshold(), 0, s.confirm, false,
                    false, remainingSec(s.lastEventAtMs + cfg.cooldownMs(), now),
                    remainingSec(s.silentUntilMs, now), s.lastError);
        }
        if (jpeg == null || jpeg.length == 0) {
            s.lastError = "取帧为空";
            return new SampleResult(did, 0, cfg.threshold(), 0, s.confirm, false,
                    false, 0, remainingSec(s.silentUntilMs, now), s.lastError);
        }

        BufferedImage img = decode(jpeg);
        if (img == null) {
            s.lastError = "JPEG 解码失败";
            return new SampleResult(did, 0, cfg.threshold(), 0, s.confirm, false,
                    false, 0, remainingSec(s.silentUntilMs, now), s.lastError);
        }
        HashResult h = hash(img);
        if (h == null) {
            s.lastError = "JPEG 解码失败";
            return new SampleResult(did, 0, cfg.threshold(), 0, s.confirm, false,
                    false, 0, remainingSec(s.silentUntilMs, now), s.lastError);
        }

        // 无信号 / 纯色帧（方差极低）：摄像头断流常呈均匀灰屏，与真实画面帧差极大，会被误判为
        // “画面变动”；且 real→gray 与 gray→real 的跳变都会被判成运动。故整段无信号期间不触发，
        // 信号恢复后的首帧当作新基准（不触发），避免灰屏被误报为事件。
        // 仅对「非低照度」的纯色帧判为无信号：纯黑/近全黑仍交给原有低照度分支处理，
        // 保持「低照度帧已跳过」语义与文案不变，也避免把夜间正常画面误判成断流灰屏。
        //
        // v1.2.16 追加「残缺帧」：P 帧缺参考帧时解码器输出大片纯灰 + 局部半透明残差，方差未必
        // 低到无信号阈值，却与正常画面差异极大，会被误判成画面变动并存成糊屏事件快照。
        // 与无信号同样处理：整段跳过，恢复后首帧重新作为基准。
        boolean lowLuma = h.luma() < MIN_LUMA;
        boolean noSignal = !lowLuma && h.std() < NOSIGNAL_STD;
        boolean broken = !lowLuma && !noSignal && FrameQuality.isBroken(img);
        if (noSignal || broken) {
            s.inNoSignal = true;
            s.baselineBits = h.bits();
            s.hasBaseline = true;
            s.confirm = 0;
            s.lastScore = 0;
            s.lastLuma = h.luma();
            s.lastSampleAtMs = now;
            s.samples++;
            s.lastError = broken ? "残缺/糊帧已跳过" : "无信号/纯色帧已跳过";
            return new SampleResult(did, 0, cfg.threshold(), h.luma(), 0, false, false,
                    remainingSec(s.lastEventAtMs + cfg.cooldownMs(), now),
                    remainingSec(s.silentUntilMs, now), s.lastError);
        }
        if (s.inNoSignal) {
            // 从无信号恢复：把当前帧当首帧基准，避免 gray→real 被判成运动
            s.inNoSignal = false;
            s.hasBaseline = false;
        }

        // 与「稳定基准帧」比较而非上一帧：画面变化一次后稳定时，若只比上一帧就只有 1 帧高分，
        // 连续确认永远不成立；而基准帧保持不变，持续偏离才会连续确认，缓慢光照漂移则因
        // 每帧低于阈值被基准跟随吸收。
        // 注意：64 bit 左移后最高位可能为 1 使 long 变负数，不能用 < 0 表示「无基准」
        //
        // v1.2.19 排除区域（ROI）：帧差只统计掩码内哈希块的汉明距离，排除区（树影、马路、
        // 常亮屏幕等）不贡献变化分数。掩码为全 0（整幅画面都被排除）时视为无效配置，
        // 不触发任何事件，仅透出提示避免静默失效。
        boolean firstFrame = !s.hasBaseline;
        int effectiveBlocks = Long.bitCount(mask);
        double score;
        if (firstFrame || effectiveBlocks == 0) {
            score = 0.0;
        } else {
            score = Long.bitCount((s.baselineBits ^ h.bits()) & mask) / (double) effectiveBlocks;
        }
        s.lastScore = score;
        s.lastLuma = h.luma();
        s.lastSampleAtMs = now;
        s.samples++;
        // 全画面被排除时透出提示，避免「框满全画面 = 静默失效」无人察觉
        s.lastError = (!firstFrame && effectiveBlocks == 0)
                ? "排除区域覆盖全画面，本帧未判定" : "";

        boolean silent = now < s.silentUntilMs;
        if (firstFrame || h.luma() < MIN_LUMA) {
            s.baselineBits = h.bits();
            s.hasBaseline = true;
            s.confirm = 0;
            if (h.luma() < MIN_LUMA) {
                s.lastError = "低照度帧已跳过";
            }
            return new SampleResult(did, score, cfg.threshold(), h.luma(), 0, false,
                    silent, remainingSec(s.lastEventAtMs + cfg.cooldownMs(), now),
                    remainingSec(s.silentUntilMs, now), s.lastError);
        }

        if (silent) {
            s.baselineBits = h.bits();
            s.hasBaseline = true;
            s.confirm = 0;
            return new SampleResult(did, score, cfg.threshold(), h.luma(), 0, false,
                    true, remainingSec(s.lastEventAtMs + cfg.cooldownMs(), now),
                    remainingSec(s.silentUntilMs, now), "");
        }

        boolean cooled = now - s.lastEventAtMs >= cfg.cooldownMs();
        if (score >= cfg.threshold()) {
            // 画面确实在变化（即便处于事件冷却期）：供自动录像状态机延长录制窗口。
            // 静默期（云台转动）与低照度帧不更新，避免把机械转动误当成持续运动。
            s.lastMotionAtMs = now;
            // 冷却期内不累加确认，避免冷却一结束就凭旧变化立刻再触发一条
            s.confirm = cooled ? s.confirm + 1 : 0;
        } else {
            s.confirm = 0;
            s.baselineBits = h.bits(); // 画面回到稳定 / 缓慢漂移：基准跟随
            s.hasBaseline = true;
        }
        boolean trigger = cooled && s.confirm >= cfg.confirmFrames();
        if (trigger) {
            String name = deviceName(did);
            // v1.2.21 持续变动合并：同设备连续触发在 4 倍冷却窗口内合并进最近一条（repeat+1），
            // 避免长时间持续运动（如家人在客厅活动一整晚）把事件页刷成同质内容
            events.addOrMergeMotion(did, name, "检测到画面变化", score, jpeg,
                    cfg.cooldownMs() * 4);
            s.triggered++;
            s.lastEventAtMs = now;
            s.confirm = 0;
            s.baselineBits = h.bits(); // 新画面成为基准，静止后不再重复报警
            s.hasBaseline = true;
            log.info("[{}] 运动侦测触发 event，score={} luma={}", did, round3(score), h.luma());
        }
        return new SampleResult(did, score, cfg.threshold(), h.luma(), s.confirm, trigger,
                false, remainingSec(s.lastEventAtMs + cfg.cooldownMs(), now), 0L, "");
    }

    /** 设备显示名；找不到时回落 DID。也供自动录像写事件时使用。 */
    public String deviceName(String did) {
        try {
            return catalog.find(did).map(CameraDevice::name).orElse(did);
        } catch (Throwable t) {
            return did;
        }
    }

    /**
     * 8×8 灰度感知哈希：缩到 8×8 灰度图，以 64 像素均值二值化成 64 bit，返回 bits 与平均亮度。
     * 只需 JDK 自带 java.desktop（fpk 内 JRE 已含），不引入任何新依赖。
     */
    static HashResult hash(byte[] jpeg) {
        BufferedImage img = decode(jpeg);
        return img == null ? null : hash(img);
    }

    /** 解码一次 JPEG，供哈希与 {@link FrameQuality} 质量判定共用，避免重复解码。 */
    static BufferedImage decode(byte[] jpeg) {
        try {
            return ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** 对已解码图像做 8×8 感知哈希（调用方保证 img 非 null）。 */
    static HashResult hash(BufferedImage img) {
        try {
            BufferedImage small = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = small.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, 8, 8, null);
            } finally {
                g.dispose();
            }
            int[] v = new int[HASH_BITS];
            long sum = 0;
            int i = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int lum = small.getRaster().getSample(x, y, 0) & 0xFF;
                    v[i++] = lum;
                    sum += lum;
                }
            }
            double avg = sum / (double) HASH_BITS;
            long bits = 0;
            double varSum = 0;
            for (int lum : v) {
                bits = (bits << 1) | (lum >= avg ? 1L : 0L);
                double d = lum - avg;
                varSum += d * d;
            }
            int std = (int) Math.round(Math.sqrt(varSum / HASH_BITS));
            return new HashResult(bits, (int) avg, std);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long remainingSec(long untilMs, long now) {
        return untilMs <= now ? 0L : (untilMs - now + 999) / 1000;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 单帧哈希结果：感知哈希位、平均亮度、8×8 灰度亮度标准差（用于无信号/纯色帧判定）。 */
    record HashResult(long bits, int luma, int std) {
    }

    /** 一次采样的完整判定明细，便于调参时解释「为什么触发 / 为什么没触发」。 */
    public record SampleResult(String did, double score, double threshold, int luma, int confirm,
                               boolean triggered, boolean silent, long cooldownRemainingSec,
                               long silentRemainingSec, String error) {
    }

    private static final class State {
        boolean hasBaseline = false;
        boolean inNoSignal = false;
        long baselineBits = 0;
        int confirm = 0;
        long lastEventAtMs = 0;
        long silentUntilMs = 0;
        long lastSampleAtMs = 0;
        long lastMotionAtMs = 0;
        long samples = 0;
        long triggered = 0;
        double lastScore = 0;
        int lastLuma = 0;
        int excludedBlocks = 0;
        String lastError = "";
    }
}
