package com.invr.web.api;

import com.invr.hub.StreamHub;
import com.invr.model.CameraDevice;
import com.invr.rtsp.RtspServer;
import com.invr.store.AppSettingsStore;
import com.invr.store.EventStore;
import com.invr.store.MotionSettings;
import com.invr.web.service.DeviceCatalog;
import com.invr.web.service.HlsService;
import com.invr.web.service.LiveFrameService;
import com.invr.web.service.RecordingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 首页概览聚合：一次请求返回设备、取流、录像、告警与侦测的运行全貌。
 *
 * <p>全部数据来自各服务已有的内存状态或本地目录，<b>不新建取流订阅、不调用米家云</b>；
 * 每个数据源独立容错，单项异常（如未登录、录像目录暂不可读）只让该项回落到零值，
 * 不会让整个首页概览 500。接口位于 {@code /api/**}，自动沿用访问密码门禁。</p>
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DeviceCatalog catalog;
    private final RecordingService recordings;
    private final EventStore events;
    private final StreamHub hub;
    private final HlsService hls;
    private final AppSettingsStore settings;
    private final LiveFrameService liveFrames;
    private final RtspServer rtspServer;

    public DashboardController(DeviceCatalog catalog, RecordingService recordings,
                               EventStore events, StreamHub hub, HlsService hls,
                               AppSettingsStore settings, LiveFrameService liveFrames,
                               RtspServer rtspServer) {
        this.catalog = catalog;
        this.recordings = recordings;
        this.events = events;
        this.hub = hub;
        this.hls = hls;
        this.settings = settings;
        this.liveFrames = liveFrames;
        this.rtspServer = rtspServer;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", System.currentTimeMillis());

        List<CameraDevice> devices = safe(() -> catalog.all(), List.of());
        int online = 0;
        int recording = 0;
        int previewing = 0;
        for (CameraDevice d : devices) {
            if (d.online()) {
                online++;
            }
            if (safe(() -> recordings.isRecording(d.did()), false)) {
                recording++;
            }
            if (safe(() -> hls.isPreviewing(d.did()), false)) {
                previewing++;
            }
        }
        Map<String, Object> dev = new LinkedHashMap<>();
        dev.put("total", devices.size());
        dev.put("online", online);
        dev.put("offline", devices.size() - online);
        dev.put("recording", recording);
        dev.put("previewing", previewing);
        out.put("devices", dev);

        Map<String, Object> streams = new LinkedHashMap<>();
        streams.put("activeUpstreams", safe(hub::activeUpstreamCount, 0));
        streams.put("activeDids", safe(hub::activeDids, List.of()));
        streams.put("activeFrameSources", safe(liveFrames::activeSources, 0));
        out.put("streams", streams);

        List<Map<String, Object>> files = safe(() -> {
            try {
                return recordings.list(null);
            } catch (java.io.IOException e) {
                return List.of();
            }
        }, List.of());
        long bytes = 0;
        for (Map<String, Object> f : files) {
            Object size = f.get("size");
            if (size instanceof Number n) {
                bytes += n.longValue();
            }
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("files", files.size());
        rec.put("bytes", bytes);
        rec.put("dir", safe(() -> recordings.rootDir().getAbsolutePath(), ""));
        out.put("recordings", rec);

        Map<String, Object> ev = new LinkedHashMap<>(safe(events::stats, Map.of()));
        ev.putIfAbsent("total", 0);
        ev.putIfAbsent("today", 0);
        ev.putIfAbsent("unhandled", 0);
        out.put("events", ev);

        MotionSettings motion = safe(settings::motion, MotionSettings.DEFAULTS);
        out.put("motion", Map.of("enabled", motion.enabled(), "threshold", motion.threshold()));
        out.put("rtspPort", safe(rtspServer::getPort, -1));
        return out;
    }

    /** 单项数据源失败时回落，避免一个不可用模块拖垮整个概览。 */
    private static <T> T safe(Supplier<T> supplier, T fallback) {
        try {
            T v = supplier.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
