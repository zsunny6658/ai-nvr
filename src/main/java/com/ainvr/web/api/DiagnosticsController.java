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

import java.time.Instant;
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
        String name = "ainvr-diagnostics-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.now())
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
