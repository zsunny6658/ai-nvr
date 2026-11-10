package com.invr.web.api;

import com.invr.hub.StreamHub;
import com.invr.model.CameraDevice;
import com.invr.store.AppSettingsStore;
import com.invr.web.service.DeviceCatalog;
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
 * 云台（PTZ）控制：在已有的唯一上游取流会话上发送 MISS 0x112 命令。
 *
 * <p>这是「连接复用」的延伸——控制命令不再另开一条到摄像头的连接，因此不会挤占
 * 米家 App 等终端本来就有限的并发通道。没有活跃预览/录像时返回 503，由调用方先打开预览。</p>
 *
 * <p>协议限制：MISS 没有「设备是否带云台电机」的可靠查询，部分固定镜头机型会静默忽略命令。
 * 设备的应答由 MISS 命令通道排空线程写入日志，可据此现场确认兼容性。</p>
 */
@RestController
@RequestMapping("/api/ptz")
public class PtzController {

    private static final List<String> DIRECTIONS = List.of("left", "right", "up", "down", "stop");

    private final StreamHub hub;
    private final DeviceCatalog catalog;
    private final AppSettingsStore settingsStore;

    public PtzController(StreamHub hub, DeviceCatalog catalog, AppSettingsStore settingsStore) {
        this.hub = hub;
        this.catalog = catalog;
        this.settingsStore = settingsStore;
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
        if (catalog.find(did).isEmpty()) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        String mode = settingsStore.ptzMode();
        boolean sent = hub.motor(did, direction, speed, mode);
        if (!sent) {
            throw new ApiException(503,
                    "该摄像头当前没有可用的取流会话，请先打开实时预览或开始录像，再使用云台控制");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("did", did);
        m.put("direction", direction);
        m.put("speed", speed);
        m.put("mode", mode);
        m.put("reusedSession", true);
        return m;
    }

    /** 云台能力说明（型号无法可靠推断是否带电机，明确返回 unknown 供 UI 提示）。 */
    @GetMapping("/{did}/capabilities")
    public Map<String, Object> capabilities(@PathVariable String did) {
        CameraDevice c = catalog.find(did).orElseThrow(() ->
                ApiException.notFound("设备列表中未找到 did=" + did));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("did", did);
        m.put("model", c.model());
        m.put("motorSupport", "unknown");
        m.put("mode", settingsStore.ptzMode());
        m.put("sessionHealthy", hub.isHealthy(did));
        m.put("note", "MISS 协议不提供云台能力查询；固定镜头机型可能静默忽略命令，请实测后查看应用日志中的设备应答。");
        return m;
    }

    public record PtzRequest(String direction, int speed) {
    }
}
