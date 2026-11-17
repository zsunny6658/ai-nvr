package com.ainvr.store;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 运动侦测运行参数（对应设置文件 {@code settings.json} 的 {@code motion} 子对象）。
 *
 * <p>默认<strong>关闭</strong>：开启前不会订阅任何兼容画面帧源，也就不会额外占用摄像头上游
 * 或解码 CPU；关闭超过 {@code LiveFrameService} 的空闲回收时间后订阅自动释放。所有参数在
 * 每轮巡检时重新读取，修改后无需重启应用。</p>
 *
 * <p>字段在紧凑构造器里统一夹到安全区间，避免手工编辑 settings.json 写入越界值导致
 * 定时器空转或事件无限增长。{@code dids} 为空表示对所有在线设备启用。</p>
 *
 * <p>v1.2.8 增加 {@code autoRecord} / {@code recordTailSec}：运动触发的自动录像默认关闭，
 * 开启后由 {@code MotionRecordingService} 管理临时录像会话（不写"持续录像"期望状态，因此
 * 进程重启不会变成永久录像）。保留 9 参数构造器，避免旧代码/旧单测被迫改动。</p>
 *
 * <p>v1.2.21 增加 {@code scheduleEnabled} / {@code activeStartMin} / {@code activeEndMin}：
 * 侦测时段计划（如仅夜间侦测）。支持跨午夜（start > end 表示跨天窗口），默认全天（0~1440）。
 * 关闭计划或时段之外时该轮巡检不采样、不产生事件。</p>
 */
public record MotionSettings(
        boolean enabled,
        double threshold,
        int intervalSec,
        int cooldownSec,
        int confirmFrames,
        int silentSec,
        int retentionDays,
        int maxEvents,
        List<String> dids,
        boolean autoRecord,
        int recordTailSec,
        boolean scheduleEnabled,
        int activeStartMin,
        int activeEndMin) {

    public static final MotionSettings DEFAULTS =
            new MotionSettings(false, 0.12, 2, 30, 2, 8, 30, 2000, List.of(), false, 20, false, 0, 1440);

    /** 兼容 v1.2.6/1.2.7 的 9 参数构造：自动录像保持关闭、尾延时用默认值。 */
    public MotionSettings(boolean enabled, double threshold, int intervalSec, int cooldownSec,
                          int confirmFrames, int silentSec, int retentionDays, int maxEvents,
                          List<String> dids) {
        this(enabled, threshold, intervalSec, cooldownSec, confirmFrames, silentSec,
                retentionDays, maxEvents, dids, false, 20, false, 0, 1440);
    }

    /** 兼容 v1.2.8~1.2.20 的 11 参数构造：无侦测时段计划。 */
    public MotionSettings(boolean enabled, double threshold, int intervalSec, int cooldownSec,
                          int confirmFrames, int silentSec, int retentionDays, int maxEvents,
                          List<String> dids, boolean autoRecord, int recordTailSec) {
        this(enabled, threshold, intervalSec, cooldownSec, confirmFrames, silentSec,
                retentionDays, maxEvents, dids, autoRecord, recordTailSec, false, 0, 1440);
    }

    public MotionSettings {
        threshold = clamp(threshold, 0.02, 0.50);
        intervalSec = (int) clamp(intervalSec, 1, 60);
        cooldownSec = (int) clamp(cooldownSec, 5, 3600);
        confirmFrames = (int) clamp(confirmFrames, 1, 10);
        silentSec = (int) clamp(silentSec, 0, 120);
        retentionDays = (int) clamp(retentionDays, 0, 365);
        maxEvents = (int) clamp(maxEvents, 100, 20000);
        recordTailSec = (int) clamp(recordTailSec, 5, 600);
        activeStartMin = (int) clamp(activeStartMin, 0, 1440);
        activeEndMin = (int) clamp(activeEndMin, 0, 1440);

        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (dids != null) {
            for (String d : dids) {
                if (d != null && !d.isBlank()) {
                    unique.add(d.trim());
                }
            }
        }
        dids = List.copyOf(new ArrayList<>(unique));
    }

    public long cooldownMs() {
        return cooldownSec * 1000L;
    }

    public long silentMs() {
        return silentSec * 1000L;
    }

    /** 画面静止多久后停止自动录像。 */
    public long recordTailMs() {
        return recordTailSec * 1000L;
    }

    /** 该 DID 是否参与巡检；空列表表示全部在线设备。 */
    public boolean covers(String did) {
        return dids.isEmpty() || dids.contains(did);
    }

    /** 当前时刻是否在侦测时段内；未启用计划视为全天。支持跨午夜（start &gt; end）。 */
    public boolean inActiveWindow(long epochMs) {
        if (!scheduleEnabled) {
            return true;
        }
        if (activeStartMin == activeEndMin) {
            return true; // 起止相同视为全天，避免配置笔误导致侦测静默失联
        }
        // 用系统默认时区取当天分钟数（UTC 取模会在 +08:00 等时区整体偏移 8 小时）
        int min = (int) (LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs),
                ZoneId.systemDefault()).toLocalTime().toSecondOfDay() / 60);
        if (activeStartMin < activeEndMin) {
            return min >= activeStartMin && min < activeEndMin;
        }
        return min >= activeStartMin || min < activeEndMin; // 跨午夜
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
