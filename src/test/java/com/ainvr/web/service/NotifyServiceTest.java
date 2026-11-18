package com.ainvr.web.service;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import com.ainvr.store.NotificationSettings;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知推送服务测试（v1.2.19）：过滤 / 静默 / Webhook 与重试 / HMAC 验签 / 事件接入。
 */
class NotifyServiceTest {

    @TempDir
    Path dir;

    private AppSettingsStore settings;
    private EventStore events;
    private NotifyService service;
    private HttpServer http;
    private final ConcurrentLinkedQueue<byte[]> hookBodies = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> hookSigs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger hookStatus = new AtomicInteger(200);
    private final AtomicInteger hookHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        settings = Mockito.mock(AppSettingsStore.class);
        events = new EventStore(dir);
        service = new NotifyService(settings, events, new SmtpMailer(
                (host, port, tls) -> { throw new IOException("no network in test"); }), true);
        service.setBackoffForTest(new long[]{0, 0, 0});

        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/hook", ex -> {
            hookHits.incrementAndGet();
            byte[] body = ex.getRequestBody().readAllBytes();
            hookBodies.add(body);
            // 队列不允许 null：无签名头时跳过而不是抛 NPE（否则 handler 中断、连接无响应）
            String sig = ex.getRequestHeaders().getFirst("X-aiNVR-Signature");
            if (sig != null) {
                hookSigs.add(sig);
            }
            byte[] resp = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(hookStatus.get(), resp.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        http.start();
    }

    @AfterEach
    void tearDown() {
        http.stop(0);
    }

    private NotificationSettings cfg(NotificationSettings base) {
        Mockito.when(settings.notifySettings()).thenReturn(base);
        return base;
    }

    private EventStore.AlarmEvent ev(String type) {
        return events.add("did-1", "客厅摄像头", type, "info", "测试事件", 0.3, null, List.of());
    }

    // ===================== 过滤 =====================

    @Test
    void disabledOrFilteredTypesNotDelivered() {
        NotificationSettings c = cfg(new NotificationSettings(false, List.of("motion"),
                "http://x", "", false, "", 465, true, "", "", "", ""));
        assertFalse(service.deliverable(c, ev("motion")), "总开关关闭不推送");

        NotificationSettings on = new NotificationSettings(true, List.of("motion"),
                "http://x", "", false, "", 465, true, "", "", "", "");
        cfg(on);
        assertTrue(service.deliverable(on, ev("motion")));
        assertFalse(service.deliverable(on, ev("offline")), "白名单外不推送");
        assertFalse(service.deliverable(on, null));
    }

    @Test
    void fullDayQuietWindowSuppressesAll() {
        // 0..1439 全天静默 → 当前时刻必然落在静默窗口内
        NotificationSettings c = new NotificationSettings(true, List.of(), 0, 1439,
                "http://x", "", false, "", 465, true, "", "", "", "");
        cfg(c);
        assertFalse(service.deliverable(c, ev("motion")));
        // 未启用静默（起止相同）→ 不静默
        NotificationSettings d = new NotificationSettings(true, List.of(), 600, 600,
                "http://x", "", false, "", 465, true, "", "", "", "");
        assertTrue(service.deliverable(d, ev("motion")));
    }

    // ===================== Webhook =====================

    @Test
    void webhookDeliversSignedPayload() {
        String secret = "s3cr3t";
        NotificationSettings c = cfg(new NotificationSettings(true, List.of(),
                "http://127.0.0.1:" + http.getAddress().getPort() + "/hook", secret,
                false, "", 465, true, "", "", "", ""));
        String result = service.deliver(c, ev("motion"));
        assertEquals("webhook=OK", result);
        assertEquals(1, hookHits.get());
        byte[] body = hookBodies.peek();
        assertNotNull(body);
        String json = new String(body, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"did\":\"did-1\""));
        assertTrue(json.contains("\"type\":\"motion\""));
        assertEquals("sha256=" + NotifyService.hmacSha256Hex(secret, body), hookSigs.peek(),
                "签名头应为 HMAC-SHA256(body)");
    }

    @Test
    void webhookRetriesThenFails() {
        hookStatus.set(500);
        NotificationSettings c = cfg(new NotificationSettings(true, List.of(),
                "http://127.0.0.1:" + http.getAddress().getPort() + "/hook", "",
                false, "", 465, true, "", "", "", ""));
        String result = service.deliver(c, ev("offline"));
        assertEquals("webhook=FAIL(HTTP 500)", result);
        assertEquals(3, hookHits.get(), "失败应退避重试共 3 次");
    }

    @Test
    void hmacKnownVector() {
        // RFC 公开样例：HMAC-SHA256("key","The quick brown fox jumps over the lazy dog")
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                NotifyService.hmacSha256Hex("key",
                        "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)));
    }

    // ===================== 邮件 =====================

    @Test
    void mailChannelRetriesThenReports() {
        // 假 mailer：前两次失败、第三次成功
        SmtpMailer flaky = new SmtpMailer() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public String send(NotificationSettings cfg, String subject, String body) {
                return n.incrementAndGet() < 3 ? "connection reset" : null;
            }
        };
        NotifyService svc = new NotifyService(settings, events, flaky, true);
        svc.setBackoffForTest(new long[]{0, 0, 0});
        NotificationSettings c = cfg(new NotificationSettings(true, List.of(), "", "",
                true, "smtp.example.com", 465, true, "u", "p", "", "me@example.com"));
        String result = svc.deliver(c, ev("motion"));
        assertEquals("mail=OK", result);
    }

    // ===================== 事件接入（端到端） =====================

    @Test
    void eventStoreAddTriggersWebhook() {
        cfg(new NotificationSettings(true, List.of(),
                "http://127.0.0.1:" + http.getAddress().getPort() + "/hook", "",
                false, "", 465, true, "", "", "", ""));
        service.register(); // 挂到 EventStore 监听
        events.add("did-1", "客厅摄像头", "motion", "info", "端到端事件", 0.2, null, List.of());
        assertEquals(1, hookHits.get(), "事件新增应触发一次 Webhook");
        assertTrue(new String(hookBodies.peek(), StandardCharsets.UTF_8)
                .contains("端到端事件"));
    }

    @Test
    void quietWindowSuppressedEventNeverHitsWebhook() {
        NotificationSettings c = new NotificationSettings(true, List.of(), 0, 1439,
                "http://127.0.0.1:" + http.getAddress().getPort() + "/hook", "",
                false, "", 465, true, "", "", "", "");
        cfg(c);
        service.register();
        assertNull(service.deliver(c, ev("motion")), "被过滤的事件 deliver 返回 null");
        assertEquals(0, hookHits.get());
    }
}
