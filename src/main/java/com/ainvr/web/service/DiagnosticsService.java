package com.ainvr.web.service;

import com.ainvr.model.CameraDevice;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.web.api.AboutController;
import com.ainvr.web.api.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 诊断与问题反馈服务（v2.3.1）。
 *
 * <p><b>诊断报告</b>：汇总版本 / 平台 / 设备概览（DID、IP、MAC 脱敏）/ 存储水位 /
 * 设置摘要 / 前端错误记录 / app.log 尾部（同样脱敏），供「导出诊断文件」与
 * 「自动上报 Issue」共用。脱敏规则见 {@link #sanitize(String)}——报告永远不包含
 * 设备 DID 原文、局域网完整 IP、米家账号或任何令牌。</p>
 *
 * <p><b>Issue 上报</b>：Gitee 优先（国内网络可达），GitHub 兜底；两者都需要用户在
 * 设置页填入自己的令牌（仅存本地 settings.json，接口绝不回显）。未配置令牌时由
 * 前端降级为「复制报告 + 打开预填 Issue 页」，不强制要求令牌。</p>
 */
@Service
public class DiagnosticsService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsService.class);

    private static final String GITEE_REPO = "sunnymore/ai-nvr";
    private static final String GITHUB_REPO = "zsunny6658/ai-nvr";
    /** 日志尾部纳入报告的字节数（轮转上限 2MB，这里取尾部即可定位近期问题）。 */
    private static final int LOG_TAIL_BYTES = 64 * 1024;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final DeviceCatalog catalog;
    private final AppSettingsStore settings;
    private final AboutController about;
    private final LogRotationTask logRotation;
    private final ObjectMapper mapper = new ObjectMapper();

    public DiagnosticsService(DeviceCatalog catalog, AppSettingsStore settings,
                              AboutController about, LogRotationTask logRotation) {
        this.catalog = catalog;
        this.settings = settings;
        this.about = about;
        this.logRotation = logRotation;
    }

    // ===================== 概览（设置页展示） =====================

    /** 诊断概览（不含日志正文）：设置页「帮助与反馈」卡展示用。 */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", about.currentVersion());
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        m.put("java", System.getProperty("java.version"));
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        m.put("uptimeMs", Math.max(0, System.currentTimeMillis() - start));
        m.put("deviceCount", catalog.all().size());
        Path lf = logRotation.resolveLogFile();
        long logBytes = 0;
        try {
            if (lf != null && Files.isRegularFile(lf)) {
                logBytes = Files.size(lf);
            }
        } catch (Exception ignored) {
            // 日志不可读不影响概览
        }
        m.put("logBytes", logBytes);
        m.put("logAvailable", logBytes > 0);
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("gitee", settings.reportToken("gitee") != null);
        tokens.put("github", settings.reportToken("github") != null);
        m.put("tokens", tokens);
        return m;
    }

    // ===================== 报告生成 =====================

    /**
     * 生成完整诊断报告（markdown 文本）。
     *
     * @param description  用户描述（可空）
     * @param frontErrors  前端错误记录 [{t: epochMs, msg: "..."}]（可空）
     */
    public String buildReport(String description, List<Map<String, Object>> frontErrors) {
        StringBuilder b = new StringBuilder();
        b.append("# aiNVR 诊断报告\n\n");
        b.append("- 生成时间：").append(TS.format(Instant.now())).append('\n');
        b.append("- 版本：v").append(about.currentVersion()).append('\n');
        b.append("- 系统：").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.arch"))
                .append(" / Java ").append(System.getProperty("java.version")).append('\n');
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        long up = Math.max(0, System.currentTimeMillis() - start);
        b.append("- 已运行：").append(Duration.ofMillis(up).toHours()).append(" 小时 ")
                .append(Duration.ofMillis(up).toMinutesPart()).append(" 分钟\n");
        appendDeviceOverview(b);
        appendStorage(b);
        appendSettings(b);
        appendFrontErrors(b, frontErrors);
        if (description != null && !description.isBlank()) {
            b.append("\n## 用户描述\n\n").append(description.trim()).append('\n');
        }
        appendLogTail(b);
        return b.toString();
    }

    private void appendDeviceOverview(StringBuilder b) {
        List<CameraDevice> devs = catalog.all();
        b.append("- 摄像头：").append(devs.size()).append(" 台");
        if (!devs.isEmpty()) {
            b.append("（").append(devs.stream()
                    .map(d -> d.model() + "@" + maskDid(d.did()) + (d.online() ? "" : "·离线"))
                    .collect(Collectors.joining(", "))).append("）");
        }
        b.append('\n');
    }

    private void appendStorage(StringBuilder b) {
        try {
            String dir = settings.recordingDir();
            File f = dir == null || dir.isBlank() ? null : new File(dir);
            if (f != null && f.exists()) {
                long free = f.getFreeSpace();
                long total = f.getTotalSpace();
                b.append("- 录像存储：共 ").append(fmtGb(total)).append("，剩余 ")
                        .append(fmtGb(free)).append('\n');
            } else {
                b.append("- 录像存储：默认目录\n");
            }
        } catch (Exception e) {
            b.append("- 录像存储：读取失败（").append(e.toString()).append("）\n");
        }
    }

    private void appendSettings(StringBuilder b) {
        b.append("- 分段时长：").append(settings.recordSegmentSec()).append("s，保留 ")
                .append(settings.recordRetentionDays()).append(" 天，RTSP 端口 ")
                .append(settings.rtspPort()).append('\n');
        b.append("- RTSP 令牌：").append(settings.rtspToken() == null || settings.rtspToken().isBlank()
                ? "未启用" : "已启用").append("；上报令牌：")
                .append(settings.reportToken("gitee") != null ? "gitee ✓" : "gitee ✗").append('/')
                .append(settings.reportToken("github") != null ? "github ✓" : "github ✗").append('\n');
    }

    private void appendFrontErrors(StringBuilder b, List<Map<String, Object>> frontErrors) {
        if (frontErrors == null || frontErrors.isEmpty()) {
            return;
        }
        b.append("\n## 前端错误记录（最近 ").append(frontErrors.size()).append(" 条）\n\n");
        for (Map<String, Object> e : frontErrors) {
            Object t = e.get("t");
            String when = t instanceof Number n ? TS.format(Instant.ofEpochMilli(n.longValue())) : "?";
            b.append("- [").append(when).append("] ")
                    .append(sanitize(String.valueOf(e.getOrDefault("msg", "")))).append('\n');
        }
    }

    private void appendLogTail(StringBuilder b) {
        b.append("\n## 服务端日志尾部（已脱敏，最后 64KB）\n\n```text\n");
        Path lf = logRotation.resolveLogFile();
        String tail = "";
        if (lf != null) {
            try {
                long size = Files.size(lf);
                long keep = Math.min(LOG_TAIL_BYTES, size);
                byte[] bytes = new byte[(int) keep];
                try (var raf = new java.io.RandomAccessFile(lf.toFile(), "r")) {
                    raf.seek(size - keep);
                    raf.readFully(bytes);
                }
                // 对齐到行首，避免首行截断半个字符
                int from = 0;
                while (from < bytes.length && bytes[from] != '\n') {
                    from++;
                }
                if (from > 0 && from < bytes.length) {
                    from++;
                } else {
                    from = 0;
                }
                tail = new String(bytes, from, bytes.length - from, StandardCharsets.UTF_8);
            } catch (Exception e) {
                tail = "（日志读取失败：" + e + "）";
            }
        } else {
            tail = "（当前环境无 app.log 文件，日志输出到 stdout）";
        }
        b.append(sanitize(tail)).append("\n```\n");
    }

    // ===================== 脱敏 =====================

    /** 8 位以上连续数字（DID / 账号 / 时间戳以外的长数字）→ 保留前 3 后 4。 */
    private static final Pattern P_DID = Pattern.compile("\\d{8,}");
    /** IPv4 → 保留前两段。 */
    private static final Pattern P_IPV4 = Pattern.compile(
            "(\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}");
    /** MAC 地址 → 保留前 3 字节（完整 6 字节共 17 字符：2 字节×3 + 冒号×2 = 8，余下 9）。 */
    private static final Pattern P_MAC = Pattern.compile(
            "([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){2})[:0-9A-Fa-f]{9}");
    /** 令牌/密钥类字段值。 */
    private static final Pattern P_SECRET = Pattern.compile(
            "(?i)((token|password|passwd|secret|apikey|api_key|access[_-]?token|authorization)"
                    + "(\"\\s*[:=]\\s*\"?|[:=]\\s*\"?))[A-Za-z0-9._~+/=-]{8,}");

    /** 报告脱敏：抹掉 DID 原文、完整 IP、MAC、令牌类字段值。 */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String s = P_SECRET.matcher(text).replaceAll("$1***");
        s = P_MAC.matcher(s).replaceAll("$1:**:**");
        s = P_IPV4.matcher(s).replaceAll("$1.*.*");
        s = P_DID.matcher(s).replaceAll(m -> {
            String g = m.group();
            return g.substring(0, 3) + "****" + g.substring(g.length() - 4);
        });
        return s;
    }

    /** 设备 DID 展示脱敏：保留前 3 后 4（不足 8 位原样返回）。 */
    public static String maskDid(String did) {
        if (did == null || did.length() < 8) {
            return did == null ? "?" : did;
        }
        return did.substring(0, 3) + "****" + did.substring(did.length() - 4);
    }

    private static String fmtGb(long bytes) {
        return String.format("%.1f GB", bytes / 1073741824.0);
    }

    // ===================== Issue 上报 =====================

    /**
     * 向 Gitee（国内优先）或 GitHub 创建 issue。令牌来自用户自填设置，未配置返回
     * {@code {error:"no_token"}} 由前端降级处理；平台接口失败抛 {@link ApiException}。
     */
    public Map<String, Object> createIssue(String platform, String title, String body) {
        String token = settings.reportToken(platform);
        if (token == null) {
            return Map.of("error", "no_token", "platform", platform);
        }
        String api;
        String authHeader;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        if ("gitee".equals(platform)) {
            api = "https://gitee.com/api/v5/repos/" + GITEE_REPO + "/issues";
            payload.put("access_token", token);
            authHeader = null;
        } else if ("github".equals(platform)) {
            api = "https://api.github.com/repos/" + GITHUB_REPO + "/issues";
            authHeader = "Bearer " + token;
        } else {
            throw new ApiException(400, "platform 仅支持 gitee / github");
        }
        try {
            var builder = java.net.http.HttpRequest.newBuilder(URI.create(api))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "aiNVR/" + about.currentVersion())
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(payload), StandardCharsets.UTF_8));
            if (authHeader != null) {
                builder.header("Authorization", authHeader);
            }
            var resp = java.net.http.HttpClient.newHttpClient()
                    .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            JsonNode node = mapper.readTree(resp.body());
            if (resp.statusCode() / 100 != 2) {
                String msg = node.path("message").asText(
                        node.path("errors").toString());
                log.warn("Issue 上报失败 platform={} http={} msg={}", platform, resp.statusCode(), msg);
                throw new ApiException(502, "上报失败（HTTP " + resp.statusCode() + "）："
                        + sanitize(msg) + "；请改用「复制报告 + 手动提 Issue」");
            }
            String url = node.path("html_url").asText("");
            if (url.isEmpty() && !node.path("number").isMissingNode()) {
                url = ("gitee".equals(platform)
                        ? "https://gitee.com/" + GITEE_REPO + "/issues/"
                        : "https://github.com/" + GITHUB_REPO + "/issues/")
                        + node.path("number").asText();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", url);
            out.put("platform", platform);
            log.info("Issue 上报成功 platform={}", platform);
            return out;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Issue 上报异常 platform={}: {}", platform, e.toString());
            throw new ApiException(502, "上报网络异常：" + e.getClass().getSimpleName()
                    + "；请改用「复制报告 + 手动提 Issue」");
        }
    }

    /** 前端「复制报告 + 手动提 Issue」降级路径用的预填链接。 */
    public Map<String, Object> prefillUrls() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gitee", "https://gitee.com/" + GITEE_REPO + "/issues/new");
        m.put("github", "https://github.com/" + GITHUB_REPO + "/issues/new");
        return m;
    }

    /** 供导出文件用的报告条目组装。 */
    public List<String> reportSections(String description, List<Map<String, Object>> frontErrors) {
        List<String> out = new ArrayList<>();
        out.add(buildReport(description, frontErrors));
        return out;
    }
}
