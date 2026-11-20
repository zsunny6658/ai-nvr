package com.ainvr.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可在 Web 端修改的应用设置持久化。
 *
 * <p>位于数据目录下的 {@code settings.json}（默认运行目录 {@code .invr/}，见 {@link DataDir}），
 * 非敏感参数，JSON 明文存储，写入采用“临时文件 + 原子 rename”。当前包含：</p>
 * <ul>
 *   <li>录像：{@code recordingDir}、{@code recordSegmentSec}、{@code rtspPort}、
 *       {@code recordRetentionDays}；</li>
 *   <li>HLS 预览：{@code hlsTargetSec}、{@code hlsWindow}、{@code hlsIdleSec}、
 *       {@code hlsAudio}、{@code hlsPrewarm}；</li>
 *   <li>共享看板：{@code shareEventDurationSec}、{@code shareSseHeartbeatSec}、
 *       {@code sharePageReloadSec}、{@code shareConfigPollSec}。</li>
 * </ul>
 *
 * <p>Web 设置优先级高于配置文件：一旦在页面保存，重启后仍以保存值为准；在页面“恢复默认”会
 * 删除对应键，回到配置文件默认值。</p>
 */
public class AppSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(AppSettingsStore.class);

    private static final String SETTINGS_FILE = "settings.json";
    private static final String KEY_RECORDING_DIR = "recordingDir";
    private static final String KEY_SEGMENT_SEC = "recordSegmentSec";
    private static final String KEY_RTSP_PORT = "rtspPort";
    private static final String KEY_RETENTION_DAYS = "recordRetentionDays";
    /** v1.2.21 录像目录容量水位（占该卷总容量百分比），0/null=关闭循环覆盖。 */
    private static final String KEY_RECORD_MAX_PERCENT = "recordMaxPercent";
    /** v1.2.22 RTSP 下行访问令牌（?t= 参数）；null/空=不鉴权。 */
    private static final String KEY_RTSP_TOKEN = "rtspToken";

    private static final String KEY_HLS_TARGET_SEC = "hlsTargetSec";
    private static final String KEY_HLS_WINDOW = "hlsWindow";
    private static final String KEY_HLS_IDLE_SEC = "hlsIdleSec";
    private static final String KEY_HLS_AUDIO = "hlsAudio";
    private static final String KEY_HLS_PREWARM = "hlsPrewarm";

    private static final String KEY_SHARE_EVENT_DURATION_SEC = "shareEventDurationSec";
    private static final String KEY_SHARE_SSE_HEARTBEAT_SEC = "shareSseHeartbeatSec";
    private static final String KEY_SHARE_PAGE_RELOAD_SEC = "sharePageReloadSec";
    private static final String KEY_SHARE_CONFIG_POLL_SEC = "shareConfigPollSec";

    /** 按 DID 的取流清晰度覆盖：{@code {did: "0|1|2|3"}}，未配置=按型号自动。 */
    private static final String KEY_DEVICE_QUALITY = "deviceQuality";
    /** 云台命令载荷格式：direction（默认）/ operation（旧固件）。 */
    private static final String KEY_PTZ_MODE = "ptzMode";
    /** 运动侦测参数（子对象，只存与默认值不同的项）。 */
    private static final String KEY_MOTION = "motion";
    /**
     * 运动侦测按设备排除区域：{@code {did: [{x,y,w,h}, ...]}}，坐标为画面归一化比例。
     * v1.2.19：排除区内的 8×8 哈希块不参与帧差比较（见 {@link MotionZone#mask64}）。
     */
    private static final String KEY_MOTION_EXCLUDE_ZONES = "motionExcludeZones";
    /** 事件通知推送（子对象，只存与默认值不同的项）。v1.2.19 新增。 */
    private static final String KEY_NOTIFY = "notify";
    /** v2.1.0 本地 AI 检测（子对象）：{enabled: bool, minScore: double}。 */
    private static final String KEY_AI = "ai";
    /** 双摄第二镜头按设备开关：{@code {did: true|false}}；未配置=按型号自动识别。 */
    private static final String KEY_DUAL_LENS = "dualLens";
    /** v2.3.1 事件快照归档：{enabled: bool, dir: 绝对路径}。 */
    private static final String KEY_SNAPSHOT_ARCHIVE = "snapshotArchive";
    private static final String KEY_MQTT = "mqtt";
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path dir;
    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public AppSettingsStore() {
        this(DataDir.get());
    }

    public AppSettingsStore(Path dir) {
        this.dir = dir;
        this.file = dir.resolve(SETTINGS_FILE);
    }

    /** 读取全部设置；文件不存在或损坏时返回空映射（不阻断启动，按默认值处理）。 */
    public synchronized Map<String, Object> all() {
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length == 0) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> m = mapper.readValue(bytes, MAP_TYPE);
            return m == null ? new LinkedHashMap<>() : m;
        } catch (IOException e) {
            log.warn("设置文件读取失败，按默认设置处理：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // ===================== 录像相关 =====================

    /** 已持久化的自定义录像目录（绝对路径）；未设置 / 为空时返回 null（沿用配置文件默认值）。 */
    public String recordingDir() {
        Object v = all().get(KEY_RECORDING_DIR);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 保存录像目录。
     *
     * @param absolutePath 绝对路径；传 null / 空白表示清除自定义、恢复配置文件默认值
     */
    public synchronized void saveRecordingDir(String absolutePath) {
        Map<String, Object> m = all();
        if (absolutePath == null || absolutePath.trim().isEmpty()) {
            m.remove(KEY_RECORDING_DIR);
        } else {
            m.put(KEY_RECORDING_DIR, absolutePath.trim());
        }
        persist(m);
    }

    /** 已持久化的自定义录像分段秒数；未设置 / 非正数时返回 null（沿用配置文件默认值）。 */
    public Integer recordSegmentSec() {
        return getPositiveInt(KEY_SEGMENT_SEC);
    }

    /** 已持久化的自定义 RTSP 端口；未设置 / 非正数时返回 null（沿用配置文件默认值）。 */
    public Integer rtspPort() {
        return getPositiveInt(KEY_RTSP_PORT);
    }

    /** 保存录像分段秒数；null / 非正数表示清除自定义、恢复默认。 */
    public void saveRecordSegmentSec(Integer sec) {
        putInt(KEY_SEGMENT_SEC, sec);
    }

    /** 保存 RTSP 端口；null / 非正数表示清除自定义、恢复默认。 */
    public void saveRtspPort(Integer port) {
        putInt(KEY_RTSP_PORT, port);
    }

    /** 已持久化的 RTSP 下行访问令牌；未设置 / 空白返回 null（不鉴权）。v1.2.22。 */
    public String rtspToken() {
        Object v = all().get(KEY_RTSP_TOKEN);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 保存 RTSP 下行访问令牌；null / 空白表示清除（恢复不鉴权）。 */
    public void saveRtspToken(String token) {
        Map<String, Object> m = all();
        if (token == null || token.trim().isEmpty()) {
            m.remove(KEY_RTSP_TOKEN);
        } else {
            m.put(KEY_RTSP_TOKEN, token.trim());
        }
        persist(m);
    }

    /** 已持久化的录像保留天数；未设置 / 非正数（0 表示永久保留）时返回 null，沿用配置默认。 */
    public Integer recordRetentionDays() {
        return getPositiveInt(KEY_RETENTION_DAYS);
    }

    /** 保存录像保留天数；null / 0 / 负数表示清除自定义、恢复默认（默认永久保留）。 */
    public void saveRecordRetentionDays(Integer days) {
        putInt(KEY_RETENTION_DAYS, days);
    }

    /** 已持久化的录像目录容量水位（占该卷总容量百分比，1~95）；未设置 / 非正数返回 null（关闭）。 */
    public Integer recordMaxPercent() {
        return getPositiveInt(KEY_RECORD_MAX_PERCENT);
    }

    /** 保存录像目录容量水位；null / 0 表示关闭水位循环覆盖。 */
    public void saveRecordMaxPercent(Integer pct) {
        putInt(KEY_RECORD_MAX_PERCENT, pct);
    }

    // ===================== HLS 预览 =====================

    public Integer hlsTargetSec() {
        return getInteger(KEY_HLS_TARGET_SEC);
    }

    public Integer hlsWindow() {
        return getInteger(KEY_HLS_WINDOW);
    }

    public Integer hlsIdleSec() {
        return getInteger(KEY_HLS_IDLE_SEC);
    }

    public Boolean hlsAudio() {
        return getBoolean(KEY_HLS_AUDIO);
    }

    public Boolean hlsPrewarm() {
        return getBoolean(KEY_HLS_PREWARM);
    }

    /**
     * 批量保存 HLS 预览设置（只写一次文件）。任一参数为 null 表示清除该键、恢复配置默认。
     */
    public synchronized void saveHls(Integer targetSec, Integer window, Integer idleSec,
                                     Boolean audio, Boolean prewarm) {
        Map<String, Object> m = all();
        putOrRemove(m, KEY_HLS_TARGET_SEC, targetSec);
        putOrRemove(m, KEY_HLS_WINDOW, window);
        putOrRemove(m, KEY_HLS_IDLE_SEC, idleSec);
        putOrRemove(m, KEY_HLS_AUDIO, audio);
        putOrRemove(m, KEY_HLS_PREWARM, prewarm);
        persist(m);
    }

    // ===================== 共享看板 =====================

    public Integer shareEventDurationSec() {
        return getInteger(KEY_SHARE_EVENT_DURATION_SEC);
    }

    public Integer shareSseHeartbeatSec() {
        return getInteger(KEY_SHARE_SSE_HEARTBEAT_SEC);
    }

    public Integer sharePageReloadSec() {
        return getInteger(KEY_SHARE_PAGE_RELOAD_SEC);
    }

    public Integer shareConfigPollSec() {
        return getInteger(KEY_SHARE_CONFIG_POLL_SEC);
    }

    /**
     * 批量保存共享看板设置（只写一次文件）。任一参数为 null 表示清除该键、恢复配置默认。
     */
    public synchronized void saveShare(Integer eventDurationSec, Integer sseHeartbeatSec,
                                       Integer pageReloadSec, Integer configPollSec) {
        Map<String, Object> m = all();
        putOrRemove(m, KEY_SHARE_EVENT_DURATION_SEC, eventDurationSec);
        putOrRemove(m, KEY_SHARE_SSE_HEARTBEAT_SEC, sseHeartbeatSec);
        putOrRemove(m, KEY_SHARE_PAGE_RELOAD_SEC, pageReloadSec);
        putOrRemove(m, KEY_SHARE_CONFIG_POLL_SEC, configPollSec);
        persist(m);
    }

    // ===================== 按设备取流清晰度 / 云台 =====================

    /** 返回该 DID 在 Web 端固定的档位；未配置返回 null（按型号自动 + 自动降级）。 */
    public String qualityFor(String did) {
        return deviceQuality().get(did);
    }

    /** 全部按设备档位覆盖（只读快照）。 */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, String> deviceQuality() {
        Object v = all().get(KEY_DEVICE_QUALITY);
        Map<String, String> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return out;
    }

    /** 保存某台设备的档位；null / 空 / "default" 表示清除覆盖、恢复自动。 */
    @SuppressWarnings("unchecked")
    public synchronized void saveDeviceQuality(String did, String quality) {
        Map<String, Object> m = all();
        Map<String, Object> q;
        Object v = m.get(KEY_DEVICE_QUALITY);
        if (v instanceof Map<?, ?> raw) {
            q = new LinkedHashMap<>((Map<String, Object>) raw);
        } else {
            q = new LinkedHashMap<>();
        }
        if (quality == null || quality.isBlank() || "default".equalsIgnoreCase(quality.trim())) {
            q.remove(did);
            if (q.isEmpty()) {
                m.remove(KEY_DEVICE_QUALITY);
            } else {
                m.put(KEY_DEVICE_QUALITY, q);
            }
        } else {
            q.put(did, quality.trim().toLowerCase());
            m.put(KEY_DEVICE_QUALITY, q);
        }
        persist(m);
    }

    /**
     * 云台命令载荷格式：{@code auto}（默认，按型号自动选择）、{@code direction}、
     * {@code operation}。步进式机型（CW400/CW500 等）必须用 operation，早先统一用
     * direction 会被设备静默忽略。
     */
    public String ptzMode() {
        Object v = all().get(KEY_PTZ_MODE);
        if (v == null) {
            return "auto";
        }
        String s = String.valueOf(v).trim().toLowerCase();
        return ("direction".equals(s) || "operation".equals(s)) ? s : "auto";
    }

    public synchronized void savePtzMode(String mode) {
        Map<String, Object> m = all();
        String s = mode == null ? "" : mode.trim().toLowerCase();
        if ("direction".equals(s) || "operation".equals(s)) {
            m.put(KEY_PTZ_MODE, s);
        } else {
            m.remove(KEY_PTZ_MODE); // auto 作为默认值不落盘
        }
        persist(m);
    }

    // ===================== 双摄第二镜头开关 =====================

    /**
     * 该设备是否手动启用 / 关闭第二镜头；未配置返回 null（按型号自动识别，
     * 见 {@code com.ainvr.miss.DualLens#knownDualLensModel}）。
     */
    public Boolean dualLensOverride(String did) {
        Object v = all().get(KEY_DUAL_LENS);
        if (!(v instanceof Map<?, ?> raw)) {
            return null;
        }
        Object o = raw.get(did);
        return o == null ? null : Boolean.parseBoolean(String.valueOf(o));
    }

    /** 保存双摄第二镜头开关；null 表示清除覆盖、恢复按型号自动识别。 */
    public synchronized void saveDualLens(String did, Boolean enabled) {
        Map<String, Object> m = all();
        Map<String, Object> sub = m.get(KEY_DUAL_LENS) instanceof Map<?, ?> r
                ? new LinkedHashMap<>((Map<String, Object>) r) : new LinkedHashMap<>();
        if (enabled == null) {
            sub.remove(did);
        } else {
            sub.put(did, enabled);
        }
        if (sub.isEmpty()) {
            m.remove(KEY_DUAL_LENS);
        } else {
            m.put(KEY_DUAL_LENS, sub);
        }
        persist(m);
    }

    // ===================== 快照归档 =====================

    /**
     * 读取事件快照归档设置；未配置或字段缺失时逐项回落默认值（关闭 / 空目录）。
     * 目录为空视为未启用，即使 enabled 为 true 也不归档。
     */
    public Map<String, Object> snapshotArchive() {
        Object v = all().get(KEY_SNAPSHOT_ARCHIVE);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", false);
        out.put("dir", "");
        if (!(v instanceof Map<?, ?> raw)) {
            return out;
        }
        out.put("enabled", Boolean.parseBoolean(String.valueOf(raw.get("enabled"))));
        Object dir = raw.get("dir");
        out.put("dir", dir == null ? "" : String.valueOf(dir).trim());
        return out;
    }

    /** 保存事件快照归档设置；dir 为空或 null 时强制关闭。 */
    public synchronized void saveSnapshotArchive(boolean enabled, String dir) {
        Map<String, Object> m = all();
        String d = dir == null ? "" : dir.trim();
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("enabled", enabled && !d.isEmpty());
        sub.put("dir", d);
        m.put(KEY_SNAPSHOT_ARCHIVE, sub);
        persist(m);
    }

    // ===================== MQTT / Home Assistant =====================

    /** MQTT / Home Assistant 接入设置（settings.json 的 {@code mqtt} 子对象，v2.3.1 新增）。 */
    public Map<String, Object> mqtt() {
        Object v = all().get(KEY_MQTT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", false);
        out.put("host", "");
        out.put("port", 1883);
        out.put("username", "");
        out.put("password", "");
        out.put("tls", false);
        out.put("discoveryPrefix", "homeassistant");
        out.put("baseTopic", "ainvr");
        out.put("offDelaySec", 90);
        out.put("snapshotIntervalSec", 30);
        if (!(v instanceof Map<?, ?> raw)) {
            return out;
        }
        out.put("enabled", Boolean.parseBoolean(String.valueOf(raw.get("enabled"))));
        out.put("host", strOrEmpty(raw.get("host")));
        out.put("port", intOr(raw.get("port"), 1883, 1, 65535));
        out.put("username", strOrEmpty(raw.get("username")));
        out.put("password", strOrEmpty(raw.get("password")));
        out.put("tls", Boolean.parseBoolean(String.valueOf(raw.get("tls"))));
        Object dp = raw.get("discoveryPrefix");
        String dpS = dp == null ? "" : String.valueOf(dp).trim();
        out.put("discoveryPrefix", dpS.isEmpty() ? "homeassistant" : dpS);
        Object bt = raw.get("baseTopic");
        String btS = bt == null ? "" : String.valueOf(bt).trim();
        out.put("baseTopic", btS.isEmpty() ? "ainvr" : btS.replaceAll("[^A-Za-z0-9_\\-]", ""));
        out.put("offDelaySec", intOr(raw.get("offDelaySec"), 90, 10, 3600));
        out.put("snapshotIntervalSec", intOr(raw.get("snapshotIntervalSec"), 30, 0, 600));
        return out;
    }

    /** 保存 MQTT 设置；enabled 时 host 必须非空。 */
    public synchronized void saveMqtt(Map<String, Object> in) {
        Map<String, Object> m = all();
        Map<String, Object> cur = mqtt();
        Map<String, Object> sub = new LinkedHashMap<>();
        boolean enabled = Boolean.parseBoolean(String.valueOf(in.getOrDefault("enabled", cur.get("enabled"))));
        String host = in.containsKey("host") ? strOrEmpty(in.get("host")) : String.valueOf(cur.get("host"));
        sub.put("enabled", enabled && !host.isEmpty());
        sub.put("host", host);
        sub.put("port", in.containsKey("port") ? intOr(in.get("port"), 1883, 1, 65535) : cur.get("port"));
        sub.put("username", in.containsKey("username") ? strOrEmpty(in.get("username")) : cur.get("username"));
        // 密码语义：显式传空串 = 清除；不传 = 保持原值（前端掩码不回显）
        if (in.containsKey("password")) {
            sub.put("password", strOrEmpty(in.get("password")));
        } else {
            sub.put("password", cur.get("password"));
        }
        sub.put("tls", Boolean.parseBoolean(String.valueOf(in.getOrDefault("tls", cur.get("tls")))));
        Object dp = in.getOrDefault("discoveryPrefix", cur.get("discoveryPrefix"));
        String dpS = strOrEmpty(dp);
        sub.put("discoveryPrefix", dpS.isEmpty() ? "homeassistant" : dpS);
        Object bt = in.getOrDefault("baseTopic", cur.get("baseTopic"));
        String btS = strOrEmpty(bt).replaceAll("[^A-Za-z0-9_\\-]", "");
        sub.put("baseTopic", btS.isEmpty() ? "ainvr" : btS);
        sub.put("offDelaySec", in.containsKey("offDelaySec")
                ? intOr(in.get("offDelaySec"), 90, 10, 3600) : cur.get("offDelaySec"));
        sub.put("snapshotIntervalSec", in.containsKey("snapshotIntervalSec")
                ? intOr(in.get("snapshotIntervalSec"), 30, 0, 600) : cur.get("snapshotIntervalSec"));
        m.put(KEY_MQTT, sub);
        persist(m);
    }

    private static String strOrEmpty(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static int intOr(Object v, int dft, int min, int max) {
        if (v == null) {
            return dft;
        }
        try {
            int n = Integer.parseInt(String.valueOf(v).trim());
            return Math.max(min, Math.min(max, n));
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    // ===================== 运动侦测 =====================

    /** 读取运动侦测参数；未配置或字段缺失时逐项回落到 {@link MotionSettings#DEFAULTS}。 */
    @SuppressWarnings("unchecked")
    public synchronized MotionSettings motion() {
        MotionSettings d = MotionSettings.DEFAULTS;
        Object v = all().get(KEY_MOTION);
        if (!(v instanceof Map<?, ?> raw)) {
            return d;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        return new MotionSettings(
                bool(m, "enabled", d.enabled()),
                num(m, "threshold", d.threshold()),
                intNum(m, "intervalSec", d.intervalSec()),
                intNum(m, "cooldownSec", d.cooldownSec()),
                intNum(m, "confirmFrames", d.confirmFrames()),
                intNum(m, "silentSec", d.silentSec()),
                intNum(m, "retentionDays", d.retentionDays()),
                intNum(m, "maxEvents", d.maxEvents()),
                strList(m.get("dids"), d.dids()),
                bool(m, "autoRecord", d.autoRecord()),
                intNum(m, "recordTailSec", d.recordTailSec()),
                bool(m, "scheduleEnabled", d.scheduleEnabled()),
                intNum(m, "activeStartMin", d.activeStartMin()),
                intNum(m, "activeEndMin", d.activeEndMin()));
    }

    /** 保存运动侦测参数（只写一次文件）；与默认值相同的项不落盘，恢复默认即删除这些键。 */
    public synchronized void saveMotion(MotionSettings s) {
        MotionSettings v = s == null ? MotionSettings.DEFAULTS : s;
        MotionSettings d = MotionSettings.DEFAULTS;
        Map<String, Object> m = all();
        Map<String, Object> sub = new LinkedHashMap<>();
        if (v.enabled() != d.enabled()) sub.put("enabled", v.enabled());
        if (Double.compare(v.threshold(), d.threshold()) != 0) sub.put("threshold", v.threshold());
        putIfChanged(sub, "intervalSec", v.intervalSec(), d.intervalSec());
        putIfChanged(sub, "cooldownSec", v.cooldownSec(), d.cooldownSec());
        putIfChanged(sub, "confirmFrames", v.confirmFrames(), d.confirmFrames());
        putIfChanged(sub, "silentSec", v.silentSec(), d.silentSec());
        putIfChanged(sub, "retentionDays", v.retentionDays(), d.retentionDays());
        putIfChanged(sub, "maxEvents", v.maxEvents(), d.maxEvents());
        if (!v.dids().equals(d.dids())) sub.put("dids", v.dids());
        if (v.autoRecord() != d.autoRecord()) sub.put("autoRecord", v.autoRecord());
        putIfChanged(sub, "recordTailSec", v.recordTailSec(), d.recordTailSec());
        if (v.scheduleEnabled() != d.scheduleEnabled()) sub.put("scheduleEnabled", v.scheduleEnabled());
        putIfChanged(sub, "activeStartMin", v.activeStartMin(), d.activeStartMin());
        putIfChanged(sub, "activeEndMin", v.activeEndMin(), d.activeEndMin());
        if (sub.isEmpty()) {
            m.remove(KEY_MOTION);
        } else {
            m.put(KEY_MOTION, sub);
        }
        persist(m);
    }

    private static void putIfChanged(Map<String, Object> m, String key, int value, int defaultV) {
        if (value != defaultV) {
            m.put(key, value);
        }
    }

    // ===================== 事件通知推送（v1.2.19） =====================

    /** 读取通知推送设置；未配置或字段缺失时逐项回落到 {@link NotificationSettings#DEFAULTS}。 */
    @SuppressWarnings("unchecked")
    public synchronized NotificationSettings notifySettings() {
        NotificationSettings d = NotificationSettings.DEFAULTS;
        Object v = all().get(KEY_NOTIFY);
        if (!(v instanceof Map<?, ?> raw)) {
            return d;
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        return new NotificationSettings(
                bool(m, "enabled", d.enabled()),
                strList(m.get("types"), d.types()),
                intNum(m, "quietStartMin", d.quietStartMin()),
                intNum(m, "quietEndMin", d.quietEndMin()),
                str(m, "webhookUrl", d.webhookUrl()),
                str(m, "webhookSecret", d.webhookSecret()),
                bool(m, "mailEnabled", d.mailEnabled()),
                str(m, "smtpHost", d.smtpHost()),
                intNum(m, "smtpPort", d.smtpPort()),
                bool(m, "smtpSsl", d.smtpSsl()),
                str(m, "smtpUser", d.smtpUser()),
                str(m, "smtpPass", d.smtpPass()),
                str(m, "mailFrom", d.mailFrom()),
                str(m, "mailTo", d.mailTo()));
    }

    /** 保存通知推送设置；与默认值相同的项不落盘。 */
    public synchronized void saveNotify(NotificationSettings s) {
        NotificationSettings v = s == null ? NotificationSettings.DEFAULTS : s;
        NotificationSettings d = NotificationSettings.DEFAULTS;
        Map<String, Object> m = all();
        Map<String, Object> sub = new LinkedHashMap<>();
        if (v.enabled() != d.enabled()) sub.put("enabled", v.enabled());
        if (!v.types().equals(d.types())) sub.put("types", v.types());
        putIfChanged(sub, "quietStartMin", v.quietStartMin(), d.quietStartMin());
        putIfChanged(sub, "quietEndMin", v.quietEndMin(), d.quietEndMin());
        if (!v.webhookUrl().equals(d.webhookUrl())) sub.put("webhookUrl", v.webhookUrl());
        if (!v.webhookSecret().equals(d.webhookSecret())) sub.put("webhookSecret", v.webhookSecret());
        if (v.mailEnabled() != d.mailEnabled()) sub.put("mailEnabled", v.mailEnabled());
        if (!v.smtpHost().equals(d.smtpHost())) sub.put("smtpHost", v.smtpHost());
        putIfChanged(sub, "smtpPort", v.smtpPort(), d.smtpPort());
        if (v.smtpSsl() != d.smtpSsl()) sub.put("smtpSsl", v.smtpSsl());
        if (!v.smtpUser().equals(d.smtpUser())) sub.put("smtpUser", v.smtpUser());
        if (!v.smtpPass().equals(d.smtpPass())) sub.put("smtpPass", v.smtpPass());
        if (!v.mailFrom().equals(d.mailFrom())) sub.put("mailFrom", v.mailFrom());
        if (!v.mailTo().equals(d.mailTo())) sub.put("mailTo", v.mailTo());
        if (sub.isEmpty()) {
            m.remove(KEY_NOTIFY);
        } else {
            m.put(KEY_NOTIFY, sub);
        }
        persist(m);
    }

    private static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    // ===================== 运动侦测排除区域（v1.2.19 ROI） =====================

    /**
     * 全部设备的排除区域快照（{@code {did: [zone...]}}）；未配置的设备不在结果里。
     * 供侦测巡检每轮一次性读取，避免逐设备重复读设置文件。
     */
    public synchronized Map<String, List<MotionZone>> motionExcludeZones() {
        Object v = all().get(KEY_MOTION_EXCLUDE_ZONES);
        Map<String, List<MotionZone>> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                if (!(e.getValue() instanceof List<?> list)) {
                    continue;
                }
                List<MotionZone> zones = new ArrayList<>();
                for (Object o : list) {
                    if (o instanceof Map<?, ?> rawZone) {
                        Map<String, Object> z = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> ze : rawZone.entrySet()) {
                            z.put(String.valueOf(ze.getKey()), ze.getValue());
                        }
                        zones.add(new MotionZone(
                                num(z, "x", 0), num(z, "y", 0),
                                num(z, "w", 0), num(z, "h", 0)));
                    }
                }
                List<MotionZone> sanitized = MotionZone.sanitize(zones);
                if (!sanitized.isEmpty()) {
                    out.put(String.valueOf(e.getKey()), sanitized);
                }
            }
        }
        return out;
    }

    /** 某设备的排除区域；未配置返回空列表（全画面参与侦测）。 */
    public synchronized List<MotionZone> excludeZonesFor(String did) {
        return motionExcludeZones().getOrDefault(did, List.of());
    }

    /**
     * 保存某设备的排除区域；空列表 / null 表示清除该设备配置、恢复全画面侦测。
     * 总面积超过 90% 视为「框满全画面」，抛出异常拒绝保存。
     */
    public synchronized void saveExcludeZones(String did, List<MotionZone> zones) {
        List<MotionZone> sanitized = MotionZone.sanitize(zones);
        if (!sanitized.isEmpty() && MotionZone.totalArea(sanitized) > 0.90) {
            throw new IllegalArgumentException("排除区域总面积超过画面 90%，请缩小范围；"
                    + "如需关闭侦测请直接关闭运动侦测开关");
        }
        Map<String, Object> m = all();
        Map<String, Object> all2 = new LinkedHashMap<>();
        Object v = m.get(KEY_MOTION_EXCLUDE_ZONES);
        if (v instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                all2.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        if (sanitized.isEmpty()) {
            all2.remove(did);
            if (all2.isEmpty()) {
                m.remove(KEY_MOTION_EXCLUDE_ZONES);
            } else {
                m.put(KEY_MOTION_EXCLUDE_ZONES, all2);
            }
        } else {
            List<Map<String, Object>> list = new ArrayList<>();
            for (MotionZone z : sanitized) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("x", z.x());
                one.put("y", z.y());
                one.put("w", z.w());
                one.put("h", z.h());
                list.add(one);
            }
            all2.put(did, list);
            m.put(KEY_MOTION_EXCLUDE_ZONES, all2);
        }
        persist(m);
    }

    private static double num(Map<String, Object> m, String key, double fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? fallback : Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int intNum(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? fallback : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return fallback;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s)) return false;
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Object v, List<String> fallback) {
        if (!(v instanceof List<?> raw)) {
            return fallback;
        }
        List<String> out = new java.util.ArrayList<>();
        for (Object o : raw) {
            if (o != null && !String.valueOf(o).isBlank()) {
                out.add(String.valueOf(o).trim());
            }
        }
        return out;
    }

    // ===================== 通用读写 =====================

    /** 正整数读取（0/负数视为未设置），供录像分段/端口/保留天数使用。 */
    private Integer getPositiveInt(String key) {
        Integer x = getInteger(key);
        return (x != null && x > 0) ? x : null;
    }

    /** 整数读取（允许 0）；不存在 / 无效返回 null。 */
    private Integer getInteger(String key) {
        Object v = all().get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 布尔读取；不存在 / 无法识别返回 null。 */
    private Boolean getBoolean(String key) {
        Object v = all().get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s)) {
            return false;
        }
        return null;
    }

    private synchronized void putInt(String key, Integer value) {
        Map<String, Object> m = all();
        if (value == null || value <= 0) {
            m.remove(key);
        } else {
            m.put(key, value);
        }
        persist(m);
    }

    private void putOrRemove(Map<String, Object> m, String key, Object value) {
        if (value == null) {
            m.remove(key);
        } else {
            m.put(key, value);
        }
    }

    private void persist(Map<String, Object> m) {
        try {
            Files.createDirectories(dir);
            Path tmp = file.resolveSibling(SETTINGS_FILE + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(m));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // 设置保存失败必须让用户感知（区别于录像状态等尽力而为的持久化），向上抛出
            throw new RuntimeException("设置保存失败：" + e.getMessage(), e);
        }
    }

    /* ===================== v2.1.0 本地 AI 检测 ===================== */

    /** 本地 AI 检测配置。 */
    public record AiSettings(boolean enabled, double minScore) {
    }

    /** AI 检测设置；默认开启、置信度 0.5。模型不可用时服务侧自行跳过。 */
    public AiSettings aiSettings() {
        boolean enabled = true;
        double minScore = 0.5;
        Object v = all().get(KEY_AI);
        if (v instanceof Map<?, ?> raw) {
            Map<String, Object> m = (Map<String, Object>) raw;
            enabled = bool(m, "enabled", true);
            minScore = doubleNum(m, "minScore", 0.5);
        }
        return new AiSettings(enabled, minScore);
    }

    /** 保存 AI 检测设置；null 表示该项维持不变。 */
    public synchronized void saveAi(Boolean enabled, Double minScore) {
        Map<String, Object> m = all();
        Object v = m.get(KEY_AI);
        Map<String, Object> sub = v instanceof Map<?, ?> r
                ? new LinkedHashMap<>((Map<String, Object>) r) : new LinkedHashMap<>();
        if (enabled != null) {
            sub.put("enabled", enabled);
        }
        if (minScore != null) {
            sub.put("minScore", minScore);
        }
        if (sub.isEmpty()) {
            m.remove(KEY_AI);
        } else {
            m.put(KEY_AI, sub);
        }
        persist(m);
    }

    private double doubleNum(Map<String, Object> m, String key, double def) {
        return m.get(key) instanceof Number n ? n.doubleValue() : def;
    }

    /**
     * v2.1.1 模型下载源 URL；未设置返回 null（使用 AiDetectService 内置默认地址）。
     * 国内 NAS 访问 GitHub 不稳时可换成 gitee 仓库附件等镜像地址。
     */
    public String aiModelUrl() {
        Object v = all().get(KEY_AI);
        if (!(v instanceof Map<?, ?> raw)) {
            return null;
        }
        Object u = ((Map<?, ?>) raw).get("modelUrl");
        if (u == null) {
            return null;
        }
        String s = String.valueOf(u).trim();
        return s.isEmpty() ? null : s;
    }

    /** 保存模型下载源；null / 空白表示清除（恢复内置默认地址）。 */
    public synchronized void saveAiModelUrl(String url) {
        Map<String, Object> m = all();
        Object v = m.get(KEY_AI);
        Map<String, Object> sub = v instanceof Map<?, ?> r
                ? new LinkedHashMap<>((Map<String, Object>) r) : new LinkedHashMap<>();
        if (url == null || url.trim().isEmpty()) {
            sub.remove("modelUrl");
        } else {
            sub.put("modelUrl", url.trim());
        }
        if (sub.isEmpty()) {
            m.remove(KEY_AI);
        } else {
            m.put(KEY_AI, sub);
        }
        persist(m);
    }

    /** v2.1.0 配置导出/导入允许写入的全部键（其余键一律丢弃，防止导入垃圾数据）。 */
    private static final java.util.Set<String> IMPORTABLE_KEYS = java.util.Set.of(
            KEY_RECORDING_DIR, KEY_SEGMENT_SEC, KEY_RTSP_PORT, KEY_RETENTION_DAYS,
            KEY_RECORD_MAX_PERCENT, KEY_RTSP_TOKEN, KEY_HLS_TARGET_SEC, KEY_HLS_WINDOW,
            KEY_HLS_IDLE_SEC, KEY_HLS_AUDIO, KEY_HLS_PREWARM, KEY_SHARE_EVENT_DURATION_SEC,
            KEY_SHARE_SSE_HEARTBEAT_SEC, KEY_SHARE_PAGE_RELOAD_SEC, KEY_SHARE_CONFIG_POLL_SEC,
            KEY_DEVICE_QUALITY, KEY_PTZ_MODE, KEY_MOTION, KEY_MOTION_EXCLUDE_ZONES, KEY_NOTIFY, KEY_AI,
            KEY_DUAL_LENS, KEY_SNAPSHOT_ARCHIVE, KEY_MQTT);

    /**
     * v2.1.0 配置导入：只接受 {@link #IMPORTABLE_KEYS} 中的键，整体替换后原子落盘。
     *
     * @return 实际写入的键（供调用方提示与副作用处理，如 RTSP 端口重绑定）
     */
    public synchronized java.util.Set<String> importAll(Map<String, Object> data) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        if (data != null) {
            for (String k : IMPORTABLE_KEYS) {
                if (data.containsKey(k)) {
                    cleaned.put(k, data.get(k));
                }
            }
        }
        persist(cleaned);
        return cleaned.keySet();
    }
}
