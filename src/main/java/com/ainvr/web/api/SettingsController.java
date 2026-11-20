package com.ainvr.web.api;

import com.ainvr.hub.StreamHub;
import com.ainvr.miss.DualLens;
import com.ainvr.miss.MissCommands;
import com.ainvr.model.CameraDevice;
import com.ainvr.model.ShareBoard;
import com.ainvr.rtsp.RtspServer;
import com.ainvr.store.AccessKeyStore;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.ShareBoardStore;
import com.ainvr.web.config.NvrProperties;
import com.ainvr.web.service.AiDetectService;
import com.ainvr.web.service.DeviceCatalog;
import com.ainvr.web.service.DeviceProbe;
import com.ainvr.web.service.HlsService;
import com.ainvr.web.service.HaMqttService;
import com.ainvr.web.service.RecordingService;
import com.ainvr.web.service.ShareEventService;
import com.ainvr.web.service.SnapshotArchiveService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private final ShareBoardStore boardStore;
    private final ObjectMapper mapper;
    private final HlsService hlsService;
    private final ShareEventService shareEventService;
    private final DeviceCatalog catalog;
    private final StreamHub streamHub;
    private final DeviceProbe deviceProbe;
    private final AiDetectService aiDetectService;
    private final SnapshotArchiveService snapshotArchive;
    private final HaMqttService mqtt;

    public SettingsController(RecordingService recordingService, RtspServer rtspServer,
                              NvrProperties props, AppSettingsStore settingsStore,
                              AccessKeyStore accessKeyStore, ShareBoardStore boardStore,
                              ObjectMapper mapper, AiDetectService aiDetectService,
                              HlsService hlsService, ShareEventService shareEventService,
                              DeviceCatalog catalog, StreamHub streamHub, DeviceProbe deviceProbe,
                              SnapshotArchiveService snapshotArchive, HaMqttService mqtt) {
        this.recordingService = recordingService;
        this.rtspServer = rtspServer;
        this.props = props;
        this.settingsStore = settingsStore;
        this.accessKeyStore = accessKeyStore;
        this.boardStore = boardStore;
        this.mapper = mapper;
        this.hlsService = hlsService;
        this.shareEventService = shareEventService;
        this.catalog = catalog;
        this.streamHub = streamHub;
        this.deviceProbe = deviceProbe;
        this.aiDetectService = aiDetectService;
        this.snapshotArchive = snapshotArchive;
        this.mqtt = mqtt;
    }

    /** 聚合返回全部设置（录像相关 + 实时 RTSP 端口）。 */
    @GetMapping("/settings")
    public Map<String, Object> getSettings() {
        Map<String, Object> m = recordingService.settingsInfo();
        m.put("rtspPort", rtspServer.getPort());
        m.put("defaultRtspPort", props.getRtspPort());
        m.put("rtspCustomized", settingsStore.rtspPort() != null);
        m.put("rtspTokenSet", rtspServer.getToken() != null && !rtspServer.getToken().isEmpty());
        m.put("hls", hlsService.hlsSettingsInfo());
        m.put("share", shareEventService.shareSettingsInfo());
        m.put("quality", qualityInfo());
        m.put("ai", aiDetectService.status());
        m.put("snapshotArchive", settingsStore.snapshotArchive());
        m.put("mqtt", mqttInfo());
        return m;
    }

    /** MQTT 设置（密码只回传是否已设置的布尔位，不回显明文）。 */
    private Map<String, Object> mqttInfo() {
        Map<String, Object> raw = settingsStore.mqtt();
        Map<String, Object> out = new java.util.LinkedHashMap<>(raw);
        boolean passSet = raw.get("password") != null && !String.valueOf(raw.get("password")).isEmpty();
        out.remove("password");
        out.put("mqttPassSet", passSet);
        out.put("ha", mqtt.status());
        return out;
    }

    /** v2.3.1 MQTT / Home Assistant 设置保存：保存后热重载 MQTT 连接。 */
    @org.springframework.web.bind.annotation.PutMapping("/settings/mqtt")
    public Map<String, Object> updateMqtt(@RequestBody(required = false) java.util.Map<String, Object> body) {
        if (body == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        settingsStore.saveMqtt(body);
        mqtt.reload();
        return mqttInfo();
    }

    /** v2.3.1 MQTT 连接测试：请求体里的 host/port/user/pass/tls 直接生效（可先测后存）。 */
    @org.springframework.web.bind.annotation.PostMapping("/settings/mqtt/test")
    public Map<String, Object> testMqtt(@RequestBody(required = false) java.util.Map<String, Object> body) {
        if (body == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        // 密码未填写时沿用已保存的（前端掩码不回显的语义）
        if (body.get("password") == null || String.valueOf(body.get("password")).isEmpty()) {
            body.put("password", settingsStore.mqtt().get("password"));
        }
        return mqtt.testConnection(body);
    }

    /** v2.3.1 事件快照归档：开关 + 归档目录（目录为空视为关闭）。 */
    @org.springframework.web.bind.annotation.PutMapping("/settings/snapshot-archive")
    public Map<String, Object> updateSnapshotArchive(@RequestBody(required = false) SnapshotArchiveRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少请求体");
        }
        String dir = req.dir() == null ? "" : req.dir().trim();
        if (req.enabled() && dir.isEmpty()) {
            throw ApiException.badRequest("请先选择归档目录");
        }
        if (req.enabled()) {
            try {
                Path d = Path.of(dir);
                Files.createDirectories(d);
                if (!Files.isWritable(d)) {
                    throw ApiException.badRequest("归档目录不可写：" + dir);
                }
            } catch (java.nio.file.InvalidPathException e) {
                throw ApiException.badRequest("归档目录路径不合法：" + dir);
            } catch (IOException e) {
                throw ApiException.badRequest("归档目录无法创建：" + e.getMessage());
            }
        }
        settingsStore.saveSnapshotArchive(req.enabled(), dir);
        return getSettings();
    }

    public record SnapshotArchiveRequest(boolean enabled, String dir) {
    }

    /** v2.1.0 本地 AI 检测设置：开关与置信度阈值（0.3～0.9）。 */
    @org.springframework.web.bind.annotation.PutMapping("/settings/ai")
    public Map<String, Object> updateAi(@RequestBody(required = false) AiRequest req) {
        if (req == null) {
            throw ApiException.badRequest("缺少参数");
        }
        if (req.minScore() != null && (req.minScore() < 0.3 || req.minScore() > 0.9)) {
            throw ApiException.badRequest("置信度阈值需在 0.3～0.9 之间");
        }
        settingsStore.saveAi(req.enabled(), req.minScore());
        return getSettings();
    }

    public record AiRequest(Boolean enabled, Double minScore) {
    }

    /**
     * v2.1.1 触发 AI 模型后台下载（模型自该版本起不内置，按需拉取）。
     * 请求体可为空或 {"url": "..."}：携带 url 时先持久化为自定义下载源再开始下载。
     * 下载在后台执行，进度通过 GET /api/settings 的 ai.modelState / downloadProgress 轮询。
     */
    @org.springframework.web.bind.annotation.PostMapping("/settings/ai-model/download")
    public Map<String, Object> downloadAiModel(@org.springframework.web.bind.annotation.RequestBody(
            required = false) AiModelDownloadRequest req) {
        String url = req == null ? null : req.url();
        if (url != null && !url.isBlank()) {
            String u = url.trim();
            if (!u.startsWith("http://") && !u.startsWith("https://")) {
                throw ApiException.badRequest("下载地址需以 http:// 或 https:// 开头");
            }
            settingsStore.saveAiModelUrl(u);
        }
        boolean started = aiDetectService.startModelDownload(
                url != null && !url.isBlank() ? url : null);
        if (!started) {
            throw ApiException.conflict("模型正在下载中，请稍候");
        }
        // 轻量返回：前端触发后会立刻重新拉取全量设置，这里只回执触发结果
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("ai", aiDetectService.status());
        return resp;
    }

    public record AiModelDownloadRequest(String url) {
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
                // 双摄第二镜头状态：secondary=true 表示这是 #2 虚拟条目本身（不显示开关）
                boolean secondary = DualLens.isSecondary(c.did());
                d.put("secondaryLens", secondary);
                if (!secondary) {
                    d.put("dualLensAuto", DualLens.knownDualLensModel(c.model()));
                    d.put("dualLensOverride", settingsStore.dualLensOverride(c.did()));
                }
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

    /**
     * 双摄第二镜头开关：true 开启（设备列表追加「（副镜头）」虚拟摄像头）、false 关闭、
     * null 清除覆盖恢复按型号自动识别。保存后立即刷新设备目录，无需重启应用。
     */
    @PutMapping("/settings/dual-lens")
    public Map<String, Object> updateDualLens(@RequestBody(required = false) DualLensRequest req) {
        if (req == null || req.did() == null || req.did().isBlank()) {
            throw ApiException.badRequest("缺少设备 DID");
        }
        if (catalog.find(DualLens.baseDid(req.did())).isEmpty()) {
            throw ApiException.notFound("设备列表中未找到 did=" + req.did());
        }
        settingsStore.saveDualLens(DualLens.baseDid(req.did()), req.enabled());
        try {
            catalog.refresh();
        } catch (Exception e) {
            log.warn("dual-lens 开关已保存，但设备目录刷新失败（后台会自动重试）：{}", e.toString());
        }
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

    /** 修改 RTSP 端口并立即重绑定；绑定失败时保留旧端口，且不落盘。可同时更换访问令牌。 */
    @PutMapping("/settings/rtsp")
    public Map<String, Object> updateRtsp(@RequestBody(required = false) RtspRequest req) {
        int port = req == null ? -1 : req.rtspPort();
        if (port < 1024 || port > 65535) {
            throw ApiException.badRequest("RTSP 端口需在 1024～65535 之间");
        }
        // v1.2.22 访问令牌：仅在请求体显式携带 rtspToken 字段时变更；
        // "-" 或空串=清除（恢复不鉴权），非空=设置并立即生效（含取流地址拼接）。
        boolean tokenChanged = req != null && req.rtspToken() != null;
        try {
            // 先在新端口绑定成功（rebind 内部保证失败时旧服务仍可用），再持久化
            rtspServer.rebind(port);
        } catch (IOException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        settingsStore.saveRtspPort(port == props.getRtspPort() ? null : port);
        if (tokenChanged) {
            String t = req.rtspToken().trim();
            if (t.isEmpty() || "-".equals(t)) {
                rtspServer.setToken(null);
                settingsStore.saveRtspToken(null);
            } else {
                if (t.length() < 4 || t.length() > 64 || t.matches(".*[\\s/?#].*")) {
                    throw ApiException.badRequest("令牌需为 4～64 个字符且不含空格 / ? # 等分隔符");
                }
                rtspServer.setToken(t);
                settingsStore.saveRtspToken(t);
            }
        }
        return getSettings();
    }

    /** 修改录像保留天数（0=永久保留，>0 时超出该天数的分段自动删除）。 */
    @PutMapping("/settings/retention")
    public Map<String, Object> updateRetention(@RequestBody(required = false) RetentionRequest req) {
        int days = req == null ? -1 : req.retentionDays();
        return recordingService.updateRetentionDays(days);
    }

    /** 修改录像目录容量水位（0=关闭循环覆盖，1~95=分段总大小占该卷总容量的百分比）。 */
    @PutMapping("/settings/water-level")
    public Map<String, Object> updateWaterLevel(@RequestBody(required = false) WaterLevelRequest req) {
        int pct = req == null ? -1 : req.waterPercent();
        return recordingService.updateWaterPercent(pct);
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
     *
     * <p>v1.2.22：{@code create=1} 时，若 path 不存在则尝试逐级创建（要求父级可写），
     * 配合目录选择器的「手动输入路径」，解决 fnOS 授权目录层级无法逐级列举时
     * 用户知道路径却选不到的问题（如 /vol2/1000 下的子目录）。</p>
     */
    @GetMapping("/settings/browse")
    public Map<String, Object> browse(@RequestParam(name = "path", required = false) String path,
                                      @RequestParam(name = "create", required = false) String create) {
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
            // v1.2.23 手动输入容错：缺前导 / 的绝对路径自动补齐（如 vol2/1000/cam → /vol2/1000/cam），
            // 否则 File.getAbsoluteFile() 会按进程 CWD（TRIM_PKGVAR）拼接出错误路径；
            // Windows 盘符（C:\ 或 C:/）除外。末尾多余 / 一并去掉。
            String p = path.trim();
            if (!p.startsWith("/") && !p.matches("^[A-Za-z]:[\\\\/].*")) {
                p = "/" + p;
            }
            while (p.length() > 1 && p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            dir = new File(p).getAbsoluteFile().getCanonicalFile();
        } catch (IOException e) {
            throw ApiException.badRequest("路径无效：" + e.getMessage());
        }
        if (!dir.exists()) {
            // v1.2.22 手动路径兜底：显式 create=1 且父级可写时自动逐级创建
            boolean wantCreate = "1".equals(create) || "true".equalsIgnoreCase(create);
            if (!wantCreate) {
                throw ApiException.badRequest("目录不存在：" + dir.getAbsolutePath()
                        + "（可在选择器中勾选「不存在时创建」后重试）");
            }
            try {
                Files.createDirectories(dir.toPath());
            } catch (IOException | SecurityException e) {
                throw ApiException.badRequest("目录不存在且自动创建失败（父级可能不可写）："
                        + dir.getAbsolutePath() + "：" + e.getMessage());
            }
        }
        if (!dir.isDirectory()) {
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
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        if (kids != null) {
            Arrays.sort(kids, Comparator.comparing((File f) -> f.getName().toLowerCase()));
            for (File k : kids) {
                Map<String, Object> e = entryOf(k, k.getName());
                if (seen.add(String.valueOf(e.get("path")))) {
                    entries.add(e);
                }
            }
        }
        // fnOS 卷/授权目录的中间层级对应用用户只有「穿越」权限、没有「列举」权限：实测
        // /vol2、/vol2/1000、/vol2/1000/影视 对 ainvr 均 Permission denied，只有授权的末级
        // /vol2/1000/影视/cam 可写。此时 listFiles 返回 null，用户从 /vol2 点进去只看到空列表，
        // 无法逐级下钻到 1000 → 影视 → cam。这里用 fnOS 注入的授权路径反推「当前目录的下一级」，
        // 把这些中间层级作为虚拟条目补进列表，使逐级下钻真正可达授权目录。
        int virtual = 0;
        try {
            Path cur = dir.toPath().toAbsolutePath().normalize();
            for (String auth : authorizedPaths()) {
                Path a = Paths.get(auth).toAbsolutePath().normalize();
                if (a.startsWith(cur) && !a.equals(cur)) {
                    String first = cur.relativize(a).getName(0).toString();
                    File child = cur.resolve(first).toFile();
                    Map<String, Object> e = entryOf(child, first);
                    if (seen.add(String.valueOf(e.get("path")))) {
                        // 中间层级可能不可写（fnOS 只授权末级），标记为「可下钻」供前端单击进入
                        e.put("virtual", true);
                        entries.add(e);
                        virtual++;
                    }
                }
            }
        } catch (Exception ignore) {
            // 路径解析异常时忽略虚拟补全，不影响真实列举结果
        }
        if (virtual > 0) {
            entries.sort(Comparator.comparing(e -> String.valueOf(e.get("name")).toLowerCase()));
        }
        resp.put("entries", entries);
        // 只要补出了可下钻的层级，就不该再提示「无权限列出内容」
        resp.put("unreadable", kids == null && entries.isEmpty());
        return resp;
    }

    /** fnOS 注入的全部授权存储路径（TRIM_DATA_* 两个环境变量，冒号分隔），用于反推目录层级。 */
    private static List<String> authorizedPaths() {
        List<String> out = new ArrayList<>();
        for (String env : new String[]{
                System.getenv("TRIM_DATA_ACCESSIBLE_PATHS"),
                System.getenv("TRIM_DATA_SHARE_PATHS")}) {
            if (env == null || env.isBlank()) {
                continue;
            }
            for (String part : env.split(":", -1)) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** 从 Servlet 上下文路径（如 /app/ainvr）推导出本应用 id；失败回落常量。 */
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
        return "ainvr";
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

    public record RtspRequest(int rtspPort, String rtspToken) {
    }

    public record RetentionRequest(int retentionDays) {
    }

    public record WaterLevelRequest(int waterPercent) {
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

    public record DualLensRequest(String did, Boolean enabled) {
    }
    /* ===================== v2.1.0 配置备份导出 / 导入 ===================== */

    /**
     * 导出全部应用配置为 JSON 下载：应用设置（含 RTSP 令牌 / 通知凭据等敏感字段）、
     * 共享看板、访问密码凭据（PBKDF2 哈希，不可逆）。米家登录会话不导出（换机需重登）。
     * 导出文件含敏感信息，提醒用户妥善保管。
     */
    @GetMapping("/settings/export")
    public ResponseEntity<Map<String, Object>> exportConfig() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", "ainvr-config-backup");
        out.put("backupVersion", 1);
        out.put("exportedAt", java.time.OffsetDateTime.now().toString());
        out.put("appSettings", settingsStore.all());
        out.put("shareBoards", boardStore.all());
        out.put("accessCredential", accessKeyStore.exportRecord());
        String fname = "ainvr-config-" + java.time.LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(out);
    }

    /**
     * 导入配置备份：应用设置整表替换（仅接受已知键）、共享看板整表替换、
     * 访问密码凭据恢复（覆盖现有）。RTSP 端口有变化时立即重绑定（失败保留运行时端口）。
     * 米家账号不受影响（会话与设备绑定关系在米家侧）。
     */
    @PostMapping("/settings/import")
    public Map<String, Object> importConfig(@RequestBody Map<String, Object> body) {
        if (!"ainvr-config-backup".equals(body.get("kind"))) {
            throw ApiException.badRequest("不是有效的 aiNVR 配置备份文件");
        }
        Number ver = body.get("backupVersion") instanceof Number n ? n : null;
        if (ver == null || ver.intValue() != 1) {
            throw ApiException.badRequest("不支持的备份版本");
        }
        Map<String, Object> imported = new LinkedHashMap<>();
        Object appSettings = body.get("appSettings");
        if (appSettings instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = (Map<String, Object>) appSettings;
            java.util.Set<String> importedKeys = settingsStore.importAll(raw);
            imported.put("appSettingsKeys", importedKeys.size());
        }
        Object boards = body.get("shareBoards");
        if (boards instanceof List) {
            List<ShareBoard> list = mapper.convertValue(boards,
                    mapper.getTypeFactory().constructCollectionType(List.class, ShareBoard.class));
            boardStore.replaceAll(list);
            imported.put("shareBoards", list.size());
        }
        Object cred = body.get("accessCredential");
        if (cred instanceof Map) {
            accessKeyStore.importRecord(mapper.convertValue(cred, AccessKeyStore.Record.class));
            imported.put("accessCredential", true);
        }
        // RTSP 端口随设置导入变化时立即重绑定；绑定失败保留当前端口（下次重启按设置值再试）
        Integer port = settingsStore.rtspPort();
        if (port != null && port > 0 && port != rtspServer.getPort()) {
            try {
                rtspServer.rebind(port);
            } catch (IOException e) {
                log.warn("导入配置后 RTSP 端口重绑定失败，保留当前端口 {}：{}", rtspServer.getPort(), e.toString());
            }
        }
        log.info("配置导入完成：{}", imported.keySet());
        return getSettings();
    }
}
