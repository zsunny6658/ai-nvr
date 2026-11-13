package com.invr.web.api;

import com.invr.hub.StreamHub;
import com.invr.miss.MissCommands;
import com.invr.model.CameraDevice;
import com.invr.rtsp.RtspServer;
import com.invr.store.AccessKeyStore;
import com.invr.store.AppSettingsStore;
import com.invr.web.config.NvrProperties;
import com.invr.web.service.DeviceCatalog;
import com.invr.web.service.DeviceProbe;
import com.invr.web.service.HlsService;
import com.invr.web.service.RecordingService;
import com.invr.web.service.ShareEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统设置：录像存储目录、录像分段时长、RTSP 端口，以及服务端目录浏览（供 Web 目录选择器使用）。
 * 均位于 {@code /api/**}，受访问密码门禁保护。
 */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final RecordingService recordingService;
    private final RtspServer rtspServer;
    private final NvrProperties props;
    private final AppSettingsStore settingsStore;
    private final AccessKeyStore accessKeyStore;
    private final HlsService hlsService;
    private final ShareEventService shareEventService;
    private final DeviceCatalog catalog;
    private final StreamHub streamHub;
    private final DeviceProbe deviceProbe;

    public SettingsController(RecordingService recordingService, RtspServer rtspServer,
                              NvrProperties props, AppSettingsStore settingsStore,
                              AccessKeyStore accessKeyStore,
                              HlsService hlsService, ShareEventService shareEventService,
                              DeviceCatalog catalog, StreamHub streamHub, DeviceProbe deviceProbe) {
        this.recordingService = recordingService;
        this.rtspServer = rtspServer;
        this.props = props;
        this.settingsStore = settingsStore;
        this.accessKeyStore = accessKeyStore;
        this.hlsService = hlsService;
        this.shareEventService = shareEventService;
        this.catalog = catalog;
        this.streamHub = streamHub;
        this.deviceProbe = deviceProbe;
    }

    /** 聚合返回全部设置（录像相关 + 实时 RTSP 端口）。 */
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> m = recordingService.settingsInfo();
        m.put("rtspPort", rtspServer.getPort());
        m.put("defaultRtspPort", props.getRtspPort());
        m.put("rtspCustomized", settingsStore.rtspPort() != null);
        m.put("hls", hlsService.hlsSettingsInfo());
        m.put("share", shareEventService.shareSettingsInfo());
        m.put("quality", qualityInfo());
        return m;
    }

    /** 按设备的取流清晰度 / 云台控制配置与实时状态。 */
    private Map<String, Object> qualityInfo() {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("options", List.of(
                Map.of("value", "default", "label", "自动（按型号，失败自动降级）"),
                Map.of("value", "0", "label", "自适应（由设备决定）"),
                Map.of("value", "1", "label", "标清 SD"),
                Map.of("value", "2", "label", "高清 HD"),
                Map.of("value", "3", "label", "超清（仅部分机型）")));
        q.put("ptzMode", settingsStore.ptzMode());
        List<Map<String, Object>> list = new ArrayList<>();
        for (CameraDevice c : catalog.all()) {
            try {
                String configured = settingsStore.qualityFor(c.did());
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("did", c.did());
                d.put("name", c.name());
                d.put("model", c.model());
                d.put("online", c.online());
                d.put("quality", configured == null ? "default" : configured);
                d.put("modelDefault", MissCommands.defaultQuality(c.model()));
                d.put("streamState", streamHub.isHealthy(c.did()) ? "有画面"
                        : (streamHub.activeDids().contains(c.did()) ? "连接中" : "未取流"));
                d.put("lanReachable", deviceProbe.reachable(c.did()));
                list.add(d);
            } catch (Exception e) {
                // 单台设备状态读取异常不应阻断其余设备与整页设置加载
                log.warn("qualityInfo 跳过设备 did={}：{}", c.did(), e.toString());
            }
        }
        q.put("devices", list);
        return q;
    }

    /**
     * 修改某台设备的取流清晰度：持久化后立即打断退避并重建上游会话，无需重启应用。
     * 传 default 恢复「按型号自动 + 失败自动降级」。
     */
    @PutMapping("/settings/quality")
    public Map<String, Object> updateQuality(@RequestBody(required = false) QualityRequest req) {
        try {
            if (req == null || req.did() == null || req.did().isBlank()) {
                throw ApiException.badRequest("缺少设备 DID");
            }
            String v = req.quality() == null ? "default" : req.quality().trim().toLowerCase();
            if (!List.of("default", "0", "1", "2", "3", "sd", "hd", "auto").contains(v)) {
                throw ApiException.badRequest("不支持的清晰度档位：" + v);
            }
            if (catalog.find(req.did()).isEmpty()) {
                log.warn("updateQuality 未命中设备 did={}（当前设备数={}）", req.did(), catalog.all().size());
                throw ApiException.notFound("设备列表中未找到 did=" + req.did());
            }
            settingsStore.saveDeviceQuality(req.did(), v);
            boolean woke = streamHub.wakeup(req.did(), "取流清晰度已修改", true);
            // 重建整页设置时若某项聚合失败（与本次保存无关），不应表现为“保存失败”
            Map<String, Object> m;
            try {
                m = getSettings();
            } catch (Exception e) {
                log.warn("updateQuality 保存成功，但聚合设置读取失败（已降级返回）：{}", e.toString());
                m = new LinkedHashMap<>();
                m.put("quality", qualityInfo());
            }
            m.put("reconnected", woke);
            return m;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("updateQuality 意外异常 did={} quality={}：{}",
                    req == null ? null : req.did(), req == null ? null : req.quality(), e.toString(), e);
            throw e;
        }
    }

    /** 切换云台命令载荷格式（auto 按型号自动；direction 通用；operation 步进式旧固件）。 */
    @PutMapping("/settings/ptz")
    public Map<String, Object> updatePtzMode(@RequestBody(required = false) PtzModeRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        String mode = req.mode() == null || req.mode().isBlank()
                ? "auto" : req.mode().trim().toLowerCase();
        if (!List.of("auto", "direction", "operation").contains(mode)) {
            throw ApiException.badRequest("云台命令格式仅支持 auto / direction / operation");
        }
        settingsStore.savePtzMode(mode);
        return getSettings();
    }

    /** 修改录像存储目录（recordingDir 为空表示恢复默认；由目录选择器选定绝对路径）。 */
    @PutMapping("/settings")
    public Map<String, Object> update(@RequestBody(required = false) SettingsRequest req) {
        recordingService.updateRecordingDir(req == null ? null : req.recordingDir());
        return getSettings();
    }

    /** 修改录像分段时长（秒，10～3600；等于配置默认值时恢复默认）。 */
    @PutMapping("/settings/segment")
    public Map<String, Object> updateSegment(@RequestBody(required = false) SegmentRequest req) {
        long sec = req == null ? -1L : req.segmentSec();
        recordingService.updateSegmentSec(sec);
        return getSettings();
    }

    /** 修改 RTSP 端口并立即重绑定；绑定失败时保留旧端口，且不落盘。 */
    @PutMapping("/settings/rtsp")
    public Map<String, Object> updateRtsp(@RequestBody(required = false) RtspRequest req) {
        int port = req == null ? -1 : req.rtspPort();
        if (port < 1024 || port > 65535) {
            throw ApiException.badRequest("RTSP 端口需在 1024～65535 之间");
        }
        try {
            // 先在新端口绑定成功（rebind 内部保证失败时旧服务仍可用），再持久化
            rtspServer.rebind(port);
        } catch (IOException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        settingsStore.saveRtspPort(port == props.getRtspPort() ? null : port);
        return getSettings();
    }

    /** 修改录像保留天数（0=永久保留，>0 时超出该天数的分段自动删除）。 */
    @PutMapping("/settings/retention")
    public Map<String, Object> updateRetention(@RequestBody(required = false) RetentionRequest req) {
        int days = req == null ? -1 : req.retentionDays();
        return recordingService.updateRetentionDays(days);
    }

    /** 修改 HLS 预览参数（分片时长/窗口/空闲/音频/预热），运行时生效并持久化。 */
    @PutMapping("/settings/hls")
    public Map<String, Object> updateHls(@RequestBody(required = false) HlsSettingsRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        hlsService.updateHls(req.targetSec(), req.window(), req.idleSec(), req.audio(), req.prewarm());
        return getSettings();
    }

    /** 修改共享看板参数（事件时长/心跳/兜底重载/配置轮询），运行时生效并持久化。 */
    @PutMapping("/settings/share")
    public Map<String, Object> updateShare(@RequestBody(required = false) ShareSettingsRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        shareEventService.updateShare(req.eventDurationSec(), req.sseHeartbeatSec(),
                req.pageReloadSec(), req.configPollSec());
        return getSettings();
    }

    /** 修改门禁访问密码（已在受保护会话内，需校验当前密码；成功后返回新的一键解锁路径）。 */
    @PutMapping("/settings/password")
    public Map<String, Object> changePassword(@RequestBody(required = false) PasswordChangeRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        char[] oldPw = req.oldPassword() == null ? null : req.oldPassword().toCharArray();
        char[] newPw = req.newPassword() == null ? null : req.newPassword().toCharArray();
        String token;
        try {
            token = accessKeyStore.changePassword(oldPw, newPw);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            throw ApiException.conflict(e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("unlockPath", "/unlock?key=" + token);
        return m;
    }

    /**
     * 服务端目录浏览：仅返回一级子目录（供弹窗目录选择器逐级展开）。
     *
     * <p>path 为空时返回文件系统根（Windows 盘符 / Linux /）；否则返回该目录下的子目录，
     * 每个条目带 {@code writable} 标识（仅可写目录允许最终选择）。浏览器无法直接访问服务端
     * 文件系统，故由该接口代为浏览。</p>
     */
    @GetMapping("/settings/browse")
    public Map<String, Object> browse(@RequestParam(name = "path", required = false) String path) {
        Map<String, Object> resp = new LinkedHashMap<>();
        File[] rootFiles = File.listRoots();
        List<Map<String, Object>> roots = new ArrayList<>();
        for (File r : rootFiles) {
            roots.add(entryOf(r, r.getPath()));
        }
        // fnOS 各盘位（/vol1 /vol2 /vol3 …）在 Linux 上 File.listRoots() 只返回 /，
        // 直接把各卷作为顶层“盘位”根列出，并优先把本应用在各卷的专属数据目录（可写）也列出，
        // 这样用户无需从 / 层层下钻即可选到其他卷存放录像。
        String appId = appId();
        for (File vol : detectFnosVolumes()) {
            roots.add(entryOf(vol, vol.getAbsolutePath()));
            if (appId != null) {
                File appData = new File(vol, "@appdata/" + appId);
                if (appData.isDirectory()) {
                    roots.add(entryOf(appData, appData.getAbsolutePath()));
                    File rec = new File(appData, "recordings");
                    if (rec.isDirectory() || rec.mkdirs()) {
                        roots.add(entryOf(rec, rec.getAbsolutePath()));
                    }
                }
            }
        }
        // fnOS 应用存储授权：用户在「应用设置→存储管理」授权的外部目录，由 fnOS 在应用启动时
        // 通过环境变量 TRIM_DATA_ACCESSIBLE_PATHS / TRIM_DATA_SHARE_PATHS（冒号分隔）注入。
        // 这些才是应用真正可访问的跨卷路径（如 /vol2/1000/影视），必须作为可选根列出；
        // 上面探测到的 /volN 顶层在 fnOS 下对应用用户不可遍历，无法直接下钻。
        addEnvRoots(roots, System.getenv("TRIM_DATA_ACCESSIBLE_PATHS"));
        addEnvRoots(roots, System.getenv("TRIM_DATA_SHARE_PATHS"));
        resp.put("roots", roots);

        // 未指定路径：展示文件系统根（盘符）
        if (path == null || path.trim().isEmpty()) {
            resp.put("current", null);
            resp.put("parent", null);
            resp.put("entries", roots);
            resp.put("unreadable", false);
            return resp;
        }

        File dir;
        try {
            dir = new File(path.trim()).getAbsoluteFile().getCanonicalFile();
        } catch (IOException e) {
            throw ApiException.badRequest("路径无效：" + e.getMessage());
        }
        if (!dir.exists() || !dir.isDirectory()) {
            throw ApiException.badRequest("不是有效的目录：" + dir.getAbsolutePath());
        }

        boolean isRoot = false;
        for (File r : rootFiles) {
            try {
                if (r.getCanonicalFile().equals(dir)) {
                    isRoot = true;
                    break;
                }
            } catch (IOException ignore) {
                // 比较盘符失败时按非根处理
            }
        }
        File parent = dir.getParentFile();
        resp.put("current", dir.getAbsolutePath());
        boolean currentWritable = false;
        try {
            currentWritable = Files.isWritable(dir.toPath());
        } catch (Exception ignore) {
            currentWritable = false;
        }
        resp.put("currentWritable", currentWritable);
        resp.put("parent", (!isRoot && parent != null) ? parent.getAbsolutePath() : null);

        File[] kids = null;
        try {
            kids = dir.listFiles(File::isDirectory);
        } catch (SecurityException ignore) {
            // 无权限列出时按空处理
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        if (kids != null) {
            Arrays.sort(kids, Comparator.comparing((File f) -> f.getName().toLowerCase()));
            for (File k : kids) {
                entries.add(entryOf(k, k.getName()));
            }
        }
        resp.put("entries", entries);
        resp.put("unreadable", kids == null);
        return resp;
    }

    /** 从 Servlet 上下文路径（如 /app/indsvr-invr）推导出本应用 id；失败回落常量。 */
    private static String appId() {
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder
                    .getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                String cp = sra.getRequest().getContextPath();
                if (cp != null && cp.startsWith("/app/")) {
                    return cp.substring("/app/".length());
                }
            }
        } catch (Exception ignore) {
            // 非请求上下文（如测试）时回落默认 id
        }
        return "indsvr-invr";
    }

    /** 探测 fnOS 盘位：根目录下名为 vol<数字> 的子目录（/vol1 /vol2 /vol3 …）。 */
    private static List<File> detectFnosVolumes() {
        List<File> vols = new ArrayList<>();
        File root = new File("/");
        File[] kids = root.listFiles(f -> f.isDirectory() && f.getName().matches("vol\\d+"));
        if (kids != null) {
            Arrays.sort(kids, Comparator.comparing(File::getName));
            for (File k : kids) {
                vols.add(k);
            }
        }
        return vols;
    }

    /** 把 fnOS 注入的授权存储路径（冒号分隔）作为可选根目录加入选择器，去重。 */
    private static void addEnvRoots(List<Map<String, Object>> roots, String env) {
        if (env == null || env.isBlank()) {
            return;
        }
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (Map<String, Object> r : roots) {
            Object p = r.get("path");
            if (p instanceof String s) {
                existing.add(s);
            }
        }
        for (String part : env.split(":", -1)) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            File f = new File(p);
            if (f.isDirectory() && existing.add(f.getAbsolutePath())) {
                roots.add(entryOf(f, f.getAbsolutePath()));
            }
        }
    }

    /** 组装一个目录条目：名称、绝对路径、是否可写。 */
    private static Map<String, Object> entryOf(File f, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean writable = false;
        try {
            writable = f.isDirectory() && Files.isWritable(f.toPath());
        } catch (Exception ignore) {
            writable = false;
        }
        m.put("name", name);
        m.put("path", f.getAbsolutePath());
        m.put("writable", writable);
        return m;
    }

    public record SettingsRequest(String recordingDir) {
    }

    public record SegmentRequest(long segmentSec) {
    }

    public record RtspRequest(int rtspPort) {
    }

    public record RetentionRequest(int retentionDays) {
    }

    public record PasswordChangeRequest(String oldPassword, String newPassword) {
    }

    public record HlsSettingsRequest(int targetSec, int window, int idleSec,
                                     boolean audio, boolean prewarm) {
    }

    public record ShareSettingsRequest(long eventDurationSec, long sseHeartbeatSec,
                                       long pageReloadSec, long configPollSec) {
    }

    public record QualityRequest(String did, String quality) {
    }

    public record PtzModeRequest(String mode) {
    }
}
