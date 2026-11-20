package com.ainvr.web.api;

import com.ainvr.hub.MotorReply;
import com.ainvr.hub.MotorResult;
import com.ainvr.hub.StreamHub;
import com.ainvr.miss.MissCommands;
import com.ainvr.model.CameraDevice;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.web.service.DeviceCatalog;
import com.ainvr.web.service.DeviceProbe;
import com.ainvr.web.service.MiotPresetService;
import com.ainvr.web.service.MotionDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云台（PTZ）控制 + 预览窗口所需的设备控制信息。
 *
 * <p>控制命令在已有的唯一上游取流会话上发送 MISS 0x112，不另开连接，因此不会挤占
 * 米家 App 等终端本来就有限的并发通道。没有活跃预览/录像时返回 503。</p>
 *
 * <p>v1.2.0：把设备的 0x113 应答回传给前端，并支持按型号自动选择命令载荷格式。
 * 此前界面点击方向键后完全无反馈，用户无法区分「命令没发出去」「设备无应答」和
 * 「已执行」三种情况。</p>
 */
@RestController
@RequestMapping("/api/ptz")
public class PtzController {

    private static final Logger log = LoggerFactory.getLogger(PtzController.class);

    private static final List<String> DIRECTIONS = List.of("left", "right", "up", "down", "stop");
    private static final List<String> MODES = List.of("auto", "direction", "operation");

    /** 与设置页一致的清晰度档位，供预览窗口就地切换。 */
    private static final List<Map<String, String>> QUALITY_OPTIONS = List.of(
            Map.of("value", "default", "label", "自动（按型号）"),
            Map.of("value", "0", "label", "自适应"),
            Map.of("value", "1", "label", "标清 SD"),
            Map.of("value", "2", "label", "高清 HD"),
            Map.of("value", "3", "label", "超清"));

    private static final List<Map<String, String>> MODE_OPTIONS = List.of(
            Map.of("value", "auto", "label", "自动（按型号）"),
            Map.of("value", "direction", "label", "direction（连续转动）"),
            Map.of("value", "operation", "label", "operation（步进式）"));

    private final StreamHub hub;
    private final DeviceCatalog catalog;
    private final AppSettingsStore settingsStore;
    private final DeviceProbe deviceProbe;
    private final MotionDetector motion;
    private final MiotPresetService presets;

    public PtzController(StreamHub hub, DeviceCatalog catalog, AppSettingsStore settingsStore,
                         DeviceProbe deviceProbe, MotionDetector motion, MiotPresetService presets) {
        this.hub = hub;
        this.catalog = catalog;
        this.settingsStore = settingsStore;
        this.deviceProbe = deviceProbe;
        this.motion = motion;
        this.presets = presets;
    }

    /** 发送一次云台动作：方向键点一次走一步；stop 用于连续运动模式的停止。 */
    @PostMapping("/{did}")
    public Map<String, Object> move(@PathVariable String did,
                                    @RequestBody(required = false) PtzRequest req) {
        String direction = req == null || req.direction() == null
                ? "" : req.direction().trim().toLowerCase();
        if (!DIRECTIONS.contains(direction)) {
            throw ApiException.badRequest("方向仅支持 left / right / up / down / stop");
        }
        int speed = req == null ? 5 : req.speed();
        if ("stop".equals(direction)) {
            speed = 0;
        } else if (speed < 1 || speed > 100) {
            speed = 5;
        }
        CameraDevice c = catalog.find(did).orElseThrow(() ->
                ApiException.notFound("设备列表中未找到 did=" + did));

        // 请求级 mode 覆盖便于在预览窗口现场试格式；不传时用全局设置（默认 auto）。
        String requested = req == null || req.mode() == null || req.mode().isBlank()
                ? settingsStore.ptzMode() : req.mode().trim().toLowerCase();
        if (!MODES.contains(requested)) {
            throw ApiException.badRequest("云台命令格式仅支持 auto / direction / operation");
        }
        String effective = "auto".equals(requested)
                ? MissCommands.motorModeFor(c.model()) : requested;

        MotorResult r = hub.motor(did, direction, speed, effective);
        if (!r.sent()) {
            log.info("云台命令未发送 did={} dir={}：没有健康的取流会话", did, direction);
            throw new ApiException(503,
                    "该摄像头当前没有可用的取流会话，请先打开实时预览或开始录像，再使用云台控制");
        }
        // 云台转动会让整幅画面剧变，运动侦测在此期间静默并重置帧差基准，避免刷出一串误报。
        motion.noteMotor(did);
        // v1.2.1：不等设备 0x113 应答，命令发出即返回。设备应答由命令通道排空线程异步
        // 记录，前端在 ~400ms / ~1s 后通过 capabilities 轮询读取，点击到界面反馈不再有固定 800ms。
        MotorReply snapshot = hub.motorReply(did);
        boolean replied = snapshot.fresh();
        log.debug("云台命令已即时返回 did={} dir={} mode={}（应答异步）", did, direction, effective);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("did", did);
        m.put("direction", direction);
        m.put("speed", speed);
        m.put("mode", requested);
        m.put("effectiveMode", effective);
        m.put("reusedSession", true);
        m.put("deviceReply", replied ? snapshot.reply() : null);
        m.put("deviceReplied", replied);
        m.put("replyPending", !replied);
        m.put("sentAt", snapshot.commandAt());
        m.put("hint", replied
                ? "设备已返回云台应答，命令被识别"
                : "命令已发出（无需等待设备应答）；若画面未转动可切换 direction / operation 重试");
        return m;
    }

    /**
     * 预览窗口打开时拉取的设备控制信息：云台能力说明 + 清晰度档位与实时状态。
     * 这样清晰度和云台格式都能在预览窗口就地切换，不必先去设置页。
     */
    @GetMapping("/{did}/capabilities")
    public Map<String, Object> capabilities(@PathVariable String did) {
        CameraDevice c = catalog.find(did).orElseThrow(() ->
                ApiException.notFound("设备列表中未找到 did=" + did));
        String configured = settingsStore.qualityFor(did);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("did", did);
        m.put("name", c.name());
        m.put("model", c.model());
        m.put("motorSupport", "unknown");
        m.put("mode", settingsStore.ptzMode());
        m.put("recommendedMode", MissCommands.motorModeFor(c.model()));
        m.put("modeOptions", MODE_OPTIONS);
        m.put("quality", configured == null ? "default" : configured);
        m.put("qualityOptions", QUALITY_OPTIONS);
        m.put("modelDefault", MissCommands.defaultQuality(c.model()));
        m.put("sessionHealthy", hub.isHealthy(did));
        m.put("streamState", hub.isHealthy(did) ? "有画面"
                : (hub.activeDids().contains(did) ? "连接中" : "未取流"));
        m.put("lanReachable", deviceProbe.reachable(did));
        // v1.2.1：前端发完命令后延时轮询本接口，读取异步到达的 0x113 应答
        MotorReply reply = hub.motorReply(did);
        m.put("lastMotorReply", reply.reply());
        m.put("lastMotorReplyAt", reply.replyAt());
        m.put("lastMotorCommandAt", reply.commandAt());
        m.put("replyFresh", reply.fresh());
        m.put("note", "MISS 协议不提供云台能力查询；固定镜头机型可能静默忽略命令。命令与设备应答均写入应用日志，预览窗口会显示设备是否应答。");
        return m;
    }

    public record PtzRequest(String direction, int speed, String mode) {
    }

    // ===================== 云台预置位（米家「常看位置」MIoT fav-area） =====================

    /**
     * 读取该设备的预置位列表。固件不支持/未设置时返回空列表（前端隐藏预置位区）。
     */
    @GetMapping("/{did}/presets")
    public Map<String, Object> listPresets(@PathVariable String did) {
        catalog.find(did).orElseThrow(() ->
                ApiException.notFound("设备列表中未找到 did=" + did));
        List<MiotPresetService.Preset> list;
        try {
            list = presets.list(did);
        } catch (Exception e) {
            log.info("[{}] 预置位列表读取失败（按不支持处理）：{}", did, e.toString());
            list = List.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("did", did);
        m.put("presets", list.stream().map(p -> Map.of("location", p.location(), "name", p.name())).toList());
        m.put("note", "预置位在米家 App 中设置（摄像头设置 → 云台 → 常看位置），aiNVR 负责展示与跳转");
        return m;
    }

    /** 跳转到指定预置位（location 为列表返回的索引值）。 */
    @PostMapping("/{did}/preset")
    public Map<String, Object> gotoPreset(@PathVariable String did,
                                          @RequestBody(required = false) PresetRequest req) {
        if (req == null || req.location() == null || req.location() < 0 || req.location() > 64) {
            throw ApiException.badRequest("location 需为 0~64 之间的预置位索引");
        }
        catalog.find(did).orElseThrow(() ->
                ApiException.notFound("设备列表中未找到 did=" + did));
        try {
            presets.gotoPreset(did, req.location());
        } catch (Exception e) {
            log.info("[{}] 预置位跳转失败：{}", did, e.toString());
            throw new ApiException(502, "预置位调用失败（米家云/固件不支持）："
                    + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        return Map.of("ok", true, "did", did, "location", req.location());
    }

    public record PresetRequest(Integer location) {
    }
}
