package com.invr.store;

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
        List<String> dids) {

    public static final MotionSettings DEFAULTS =
            new MotionSettings(false, 0.12, 2, 30, 2, 8, 30, 2000, List.of());

    public MotionSettings {
        threshold = clamp(threshold, 0.02, 0.50);
        intervalSec = (int) clamp(intervalSec, 1, 60);
        cooldownSec = (int) clamp(cooldownSec, 5, 3600);
        confirmFrames = (int) clamp(confirmFrames, 1, 10);
        silentSec = (int) clamp(silentSec, 0, 120);
        retentionDays = (int) clamp(retentionDays, 0, 365);
        maxEvents = (int) clamp(maxEvents, 100, 20000);

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

    /** 该 DID 是否参与巡检；空列表表示全部在线设备。 */
    public boolean covers(String did) {
        return dids.isEmpty() || dids.contains(did);
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
