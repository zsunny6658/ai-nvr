package com.invr.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    /** 已持久化的录像保留天数；未设置 / 非正数（0 表示永久保留）时返回 null，沿用配置默认。 */
    public Integer recordRetentionDays() {
        return getPositiveInt(KEY_RETENTION_DAYS);
    }

    /** 保存录像保留天数；null / 0 / 负数表示清除自定义、恢复默认（默认永久保留）。 */
    public void saveRecordRetentionDays(Integer days) {
        putInt(KEY_RETENTION_DAYS, days);
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
                intNum(m, "recordTailSec", d.recordTailSec()));
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
}
