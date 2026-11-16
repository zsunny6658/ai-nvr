package com.invr.web.api;

import com.invr.store.AppSettingsStore;
import com.invr.store.NotificationSettings;
import com.invr.web.service.NotifyService;
import com.invr.web.service.SmtpMailer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事件通知推送设置接口（v1.2.19 P0-1）。
 *
 * <p>{@code GET/PUT /api/notify/settings} 读写配置；SMTP 授权码等敏感字段只在 GET
 * 返回布尔"已配置"标记，不回显明文（前端保存时留空=保持原值，传 "-" 表示清除）。
 * 测试发送走 {@code POST /api/notify/test?channel=webhook|mail|all}，同步执行便于排错。</p>
 */
@RestController
@RequestMapping("/api/notify")
public class NotificationController {

    private final AppSettingsStore settingsStore;
    private final NotifyService notifyService;
    private final SmtpMailer mailer = new SmtpMailer();

    public NotificationController(AppSettingsStore settingsStore, NotifyService notifyService) {
        this.settingsStore = settingsStore;
        this.notifyService = notifyService;
    }

    @GetMapping("/settings")
    public Map<String, Object> get() {
        NotificationSettings s = settingsStore.notifySettings();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", s.enabled());
        m.put("types", s.types());
        m.put("quietStartMin", s.quietStartMin());
        m.put("quietEndMin", s.quietEndMin());
        m.put("webhookUrl", s.webhookUrl());
        // 密钥/授权码不回显明文，只告知是否已配置
        m.put("webhookSecretSet", !s.webhookSecret().isBlank());
        m.put("mailEnabled", s.mailEnabled());
        m.put("smtpHost", s.smtpHost());
        m.put("smtpPort", s.smtpPort());
        m.put("smtpSsl", s.smtpSsl());
        m.put("smtpUser", s.smtpUser());
        m.put("smtpPassSet", !s.smtpPass().isBlank());
        m.put("mailFrom", s.mailFrom());
        m.put("mailTo", s.mailTo());
        return m;
    }

    @PutMapping("/settings")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        NotificationSettings cur = settingsStore.notifySettings();
        // 敏感字段留空 = 保持原值；显式传 "-" 表示清除
        String secret = mergeSecret(strOf(body.get("webhookSecret")), cur.webhookSecret());
        String pass = mergeSecret(strOf(body.get("smtpPass")), cur.smtpPass());
        NotificationSettings next = new NotificationSettings(
                boolOf(body.get("enabled"), cur.enabled()),
                strListOf(body.get("types"), cur.types()),
                intOf(body.get("quietStartMin"), cur.quietStartMin()),
                intOf(body.get("quietEndMin"), cur.quietEndMin()),
                mergeField(strOf(body.get("webhookUrl")), cur.webhookUrl()),
                secret,
                boolOf(body.get("mailEnabled"), cur.mailEnabled()),
                mergeField(strOf(body.get("smtpHost")), cur.smtpHost()),
                intOf(body.get("smtpPort"), cur.smtpPort()),
                boolOf(body.get("smtpSsl"), cur.smtpSsl()),
                mergeField(strOf(body.get("smtpUser")), cur.smtpUser()),
                pass,
                mergeField(strOf(body.get("mailFrom")), cur.mailFrom()),
                mergeField(strOf(body.get("mailTo")), cur.mailTo()));
        settingsStore.saveNotify(next);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("webhookReady", next.webhookReady());
        out.put("mailReady", next.mailReady());
        return out;
    }

    /** 用当前配置立即发送一条测试通知；channel=webhook|mail|all。同步执行，直接返回各通道结果。 */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestParam(defaultValue = "all") String channel) {
        NotificationSettings cfg = settingsStore.notifySettings();
        Map<String, Object> out = new LinkedHashMap<>();
        String title = "通知测试（invr " + channel + "）";
        if (channel.equalsIgnoreCase("webhook") || channel.equalsIgnoreCase("all")) {
            if (!cfg.webhookReady()) {
                out.put("webhook", "未配置 webhookUrl");
            } else {
                out.put("webhook", notifyService.sendWebhookOnce(cfg, testPayload(title)));
            }
        }
        if (channel.equalsIgnoreCase("mail") || channel.equalsIgnoreCase("all")) {
            if (!cfg.mailReady()) {
                out.put("mail", "邮件通道未就绪（需要开启并填写 SMTP 服务器与收件人）");
            } else {
                String body = "这是 invr 事件通知的测试邮件。\n\n—— invr 网络录像服务";
                out.put("mail", mailer.send(cfg, "[invr] " + title, body));
            }
        }
        out.put("ok", true);
        return out;
    }

    private static Map<String, Object> testPayload(String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", -1);
        payload.put("did", "test");
        payload.put("deviceName", "测试设备");
        payload.put("type", "test");
        payload.put("level", "info");
        payload.put("title", title);
        payload.put("score", 0);
        payload.put("createdAt", System.currentTimeMillis());
        payload.put("snapshot", "");
        return payload;
    }

    // ===================== 字段合并（留空保旧值） =====================

    private static String mergeField(String incoming, String current) {
        return incoming.isBlank() ? current : incoming;
    }

    /** 密钥/授权码特殊处理：""=保旧值，"-"=清除。 */
    private static String mergeSecret(String incoming, String current) {
        if (incoming.isBlank()) {
            return current;
        }
        return "-".equals(incoming) ? "" : incoming;
    }

    private static String strOf(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static boolean boolOf(Object v, boolean fallback) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = strOf(v).toLowerCase();
        if ("true".equals(s) || "1".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s)) return false;
        return fallback;
    }

    private static int intOf(Object v, int fallback) {
        try {
            return Integer.parseInt(strOf(v));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static java.util.List<String> strListOf(Object v, java.util.List<String> fallback) {
        if (v instanceof java.util.List<?> l) {
            return l.stream().filter(x -> x != null && !String.valueOf(x).isBlank())
                    .map(x -> String.valueOf(x).trim().toLowerCase()).distinct().toList();
        }
        return fallback;
    }
}
