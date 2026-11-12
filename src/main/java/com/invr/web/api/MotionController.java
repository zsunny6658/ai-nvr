package com.invr.web.api;

import com.invr.store.AppSettingsStore;
import com.invr.store.MotionSettings;
import com.invr.web.service.MotionDetector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运动侦测的开关、参数与可观测性接口（无界面阶段用 curl 调参 / 评估误报率）。
 *
 * <p>{@code /api/motion/status} 返回每设备最近一次帧差评分、连续确认计数、触发数、冷却与
 * 静默剩余秒数，配合事件列表即可判断阈值是否过灵敏，不需要登录 NAS 翻日志。</p>
 */
@RestController
@RequestMapping("/api/motion")
public class MotionController {

    private final AppSettingsStore settingsStore;
    private final MotionDetector detector;

    public MotionController(AppSettingsStore settingsStore, MotionDetector detector) {
        this.settingsStore = settingsStore;
        this.detector = detector;
    }

    /** 当前生效参数。 */
    @GetMapping("/config")
    public MotionSettings config() {
        return settingsStore.motion();
    }

    /** 全部字段可省略，省略项沿用当前值；参数每轮巡检重新读取，保存后立即生效。 */
    public record ConfigRequest(Boolean enabled, Double threshold, Integer intervalSec,
                                Integer cooldownSec, Integer confirmFrames, Integer silentSec,
                                Integer retentionDays, Integer maxEvents, List<String> dids) {
    }

    @PutMapping("/config")
    public MotionSettings updateConfig(@RequestBody(required = false) ConfigRequest req) {
        MotionSettings cur = settingsStore.motion();
        ConfigRequest r = req == null ? new ConfigRequest(null, null, null, null,
                null, null, null, null, null) : req;

        double threshold = r.threshold() == null ? cur.threshold() : r.threshold();
        int interval = r.intervalSec() == null ? cur.intervalSec() : r.intervalSec();
        int cooldown = r.cooldownSec() == null ? cur.cooldownSec() : r.cooldownSec();
        int confirm = r.confirmFrames() == null ? cur.confirmFrames() : r.confirmFrames();
        int silent = r.silentSec() == null ? cur.silentSec() : r.silentSec();
        int retention = r.retentionDays() == null ? cur.retentionDays() : r.retentionDays();
        int maxEvents = r.maxEvents() == null ? cur.maxEvents() : r.maxEvents();

        requireRange(threshold, 0.02, 0.50, "threshold");
        requireRange(interval, 1, 60, "intervalSec");
        requireRange(cooldown, 5, 3600, "cooldownSec");
        requireRange(confirm, 1, 10, "confirmFrames");
        requireRange(silent, 0, 120, "silentSec");
        requireRange(retention, 0, 365, "retentionDays");
        requireRange(maxEvents, 100, 20000, "maxEvents");

        MotionSettings next = new MotionSettings(
                r.enabled() == null ? cur.enabled() : r.enabled(),
                threshold, interval, cooldown, confirm, silent, retention, maxEvents,
                r.dids() == null ? cur.dids() : r.dids());
        settingsStore.saveMotion(next);
        return settingsStore.motion();
    }

    /** 运行状态与逐设备评分分布。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return detector.status();
    }

    /** 立即采样一台设备（真实参与状态机，满足条件会落一条事件），返回判定明细。 */
    @PostMapping("/sample/{did}")
    public MotionDetector.SampleResult sample(@PathVariable String did) {
        return detector.sample(did);
    }

    private static void requireRange(double v, double lo, double hi, String name) {
        if (Double.isNaN(v) || v < lo || v > hi) {
            throw ApiException.badRequest(name + " 需在 " + lo + " ~ " + hi + " 之间");
        }
    }
}
