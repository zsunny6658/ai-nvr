package com.ainvr.web.api;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.web.service.DiagnosticsService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断与问题反馈端点（v2.3.1）。
 *
 * <p>均在 /api/** 门禁内，未解锁不可访问。报告内容经 {@link DiagnosticsService#sanitize}
 * 脱敏（DID/完整 IP/MAC/令牌类字段）；上报令牌由用户自填、仅存本地 settings.json，
 * 接口只回「是否已配置」，绝不回显原文。</p>
 */
@RestController
public class DiagnosticsController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DiagnosticsController.class);

    private final DiagnosticsService diagnostics;
    private final AppSettingsStore settings;

    public DiagnosticsController(DiagnosticsService diagnostics, AppSettingsStore settings) {
        this.diagnostics = diagnostics;
        this.settings = settings;
    }

    /** 诊断概览：版本/平台/设备数/日志可用性/令牌配置状态（设置页反馈卡展示）。 */
    @GetMapping("/api/diagnostics/summary")
    public Map<String, Object> summary() {
        return diagnostics.summary();
    }

    /** 导出完整诊断报告为文本文件（含日志尾部，已脱敏）。 */
    @GetMapping("/api/diagnostics/export")
    public ResponseEntity<String> export() {
        String report = diagnostics.buildReport(null, null);
        // 注意：不能用 DateTimeFormatter.ofPattern("yyyy...") 直接格式化 Instant——
        // Instant 不支持 YearOfEra 字段（UnsupportedTemporalTypeException），必须用 LocalDateTime
        String name = "ainvr-diagnostics-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now())
                + ".txt";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                .body(report);
    }

    /** 自动上报 Issue：Gitee 优先（国内可达），GitHub 兜底；未配置令牌返回 no_token 由前端降级。 */
    @PostMapping("/api/diagnostics/issue")
    public Map<String, Object> createIssue(@RequestBody Map<String, Object> body) {
        String platform = String.valueOf(body.getOrDefault("platform", "gitee"));
        String description = String.valueOf(body.getOrDefault("description", ""));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) body.get("errors");
        String version = String.valueOf(diagnostics.summary().get("version"));
        String title = firstLine(description);
        if (title == null || title.isBlank()) {
            title = "用户反馈（aiNVR v" + version + "）";
        } else if (title.length() > 80) {
            title = title.substring(0, 80);
        }
        String report = diagnostics.buildReport(description, errors);
        return diagnostics.createIssue(platform, title, report);
    }

    /** 预填 Issue 页链接（无令牌降级路径用）。 */
    @GetMapping("/api/diagnostics/prefill")
    public Map<String, Object> prefill() {
        return diagnostics.prefillUrls();
    }

    /** 版本更新检测（Gitee 优先/GitHub 兜底，服务端缓存 30 分钟；refresh=true 强制刷新）。 */
    @GetMapping("/api/diagnostics/update-check")
    public Map<String, Object> updateCheck(@org.springframework.web.bind.annotation.RequestParam(
            defaultValue = "false") boolean refresh) {
        return diagnostics.updateCheck(refresh);
    }

    /** 上报令牌配置状态（布尔值，不回显原文）。 */
    @GetMapping("/api/diagnostics/tokens")
    public Map<String, Object> tokens() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gitee", settings.reportToken("gitee") != null);
        m.put("github", settings.reportToken("github") != null);
        return m;
    }

    /** 保存 / 清除上报令牌（token 为空=清除）。 */
    @PutMapping("/api/diagnostics/tokens")
    public Map<String, Object> saveTokens(@RequestBody Map<String, Object> body) {
        String platform = String.valueOf(body.getOrDefault("platform", ""));
        String token = body.get("token") == null ? "" : String.valueOf(body.get("token"));
        if (!"gitee".equals(platform) && !"github".equals(platform)) {
            throw ApiException.badRequest("platform 仅支持 gitee / github");
        }
        settings.saveReportToken(platform, token);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    /**
     * v2.3.1 第二十二轮：客户端事件上报（轻量诊断）。前端把 WebView 端无法远程观察的
     * 行为（如 iOS 原生视频全屏劫持）写进应用日志，供真机问题远程定位。
     * 仅落 INFO 日志，不建 issue 文件、不做任何聚合。
     */
    @PostMapping("/api/diagnostics/client-log")
    public Map<String, Object> clientLog(@RequestBody Map<String, Object> body) {
        String event = String.valueOf(body.getOrDefault("event", "unknown"));
        Object detail = body.get("detail");
        log.info("[client-diag] {} {}", event, detail == null ? "" : String.valueOf(detail));
        return Map.of("ok", true);
    }

    /** 取描述的首个非空行作为 issue 标题。 */
    private static String firstLine(String s) {
        if (s == null) {
            return null;
        }
        for (String line : s.split("\r?\n")) {
            if (!line.isBlank()) {
                return line.trim();
            }
        }
        return null;
    }
}
