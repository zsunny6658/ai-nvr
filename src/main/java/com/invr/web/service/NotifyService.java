package com.invr.web.service;

import com.invr.store.AppSettingsStore;
import com.invr.store.EventStore;
import com.invr.store.NotificationSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 事件通知推送（v1.2.19 P0-1）：事件产生时经 Webhook / SMTP 邮件外发。
 *
 * <p>作为 {@link EventStore#setAddListener} 的监听方接入，收到事件后立即入队，
 * 由单线程 worker 异步处理——绝不阻塞事件写入路径，也绝不向事件主流程抛异常。
 * 过滤顺序：总开关 → 类型白名单 → 静默时段 → 各通道就绪检查。</p>
 *
 * <p>发送失败退避重试：每个通道最多 3 次（间隔 1s / 3s），重试在 worker 线程内同步进行，
 * 因此单事件最多阻塞 worker 约 2 分钟，极端情况下后续事件排队——可接受（通知不是强实时）。</p>
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = {0, 1000, 3000};
    /** 实际生效的重试间隔（测试可注入全 0，避免真实睡眠）。 */
    private volatile long[] backoff = BACKOFF_MS;

    private final AppSettingsStore settingsStore;
    private final EventStore events;
    private final SmtpMailer mailer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService workerService;
    /** 投递执行器：生产为单线程守护线程 worker；同步模式（测试）直接在调用线程执行。 */
    private final Executor worker;

    /** 主构造器：类存在多个构造器（另两个供测试注入），必须显式标注注入入口。 */
    @org.springframework.beans.factory.annotation.Autowired
    public NotifyService(AppSettingsStore settingsStore, EventStore events) {
        this(settingsStore, events, new SmtpMailer());
    }

    /** 测试用：注入假 mailer 与同步模式。 */
    NotifyService(AppSettingsStore settingsStore, EventStore events, SmtpMailer mailer,
                  boolean synchronous) {
        this.settingsStore = settingsStore;
        this.events = events;
        this.mailer = mailer;
        if (synchronous) {
            this.worker = Runnable::run;
            this.workerService = null;
        } else {
            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "notify-worker");
                t.setDaemon(true);
                return t;
            };
            this.workerService = Executors.newSingleThreadExecutor(tf);
            this.worker = workerService;
        }
    }

    NotifyService(AppSettingsStore settingsStore, EventStore events, SmtpMailer mailer) {
        this(settingsStore, events, mailer, false);
    }

    @PostConstruct
    void register() {
        events.setAddListener(this::onEvent);
        log.info("事件通知推送已接入（Webhook / SMTP）");
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        if (workerService != null) {
            workerService.shutdown();
            workerService.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** 事件入口：过滤 + 入队；任何异常吞掉，不影响事件主流程。 */
    void onEvent(EventStore.AlarmEvent e) {
        try {
            NotificationSettings cfg = settingsStore.notifySettings();
            if (!deliverable(cfg, e)) {
                return;
            }
            worker.execute(() -> deliver(cfg, e));
        } catch (RuntimeException ex) {
            log.warn("通知调度失败 eventId={}: {}", e == null ? -1 : e.id(), ex.toString());
        }
    }

    /** 同步投递（测试直调）；返回 null=未发送（被过滤），否则为各通道结果描述。 */
    String deliver(NotificationSettings cfg, EventStore.AlarmEvent e) {
        if (!deliverable(cfg, e)) {
            return null;
        }
        String subject = "[aiNVR] " + e.deviceName() + "：" + e.title();
        String body = renderBody(e);
        StringBuilder results = new StringBuilder();

        if (cfg.webhookReady()) {
            String err = sendWebhookWithRetry(cfg, e);
            results.append("webhook=").append(err == null ? "OK" : "FAIL(" + err + ")");
        }
        if (cfg.mailReady()) {
            String err = sendWithRetry(() -> mailer.send(cfg, subject, body));
            if (results.length() > 0) {
                results.append(", ");
            }
            results.append("mail=").append(err == null ? "OK" : "FAIL(" + err + ")");
        }
        if (results.length() > 0) {
            log.info("事件通知 eventId={} {}", e.id(), results);
        }
        return results.toString();
    }

    // ===================== 过滤 =====================

    boolean deliverable(NotificationSettings cfg, EventStore.AlarmEvent e) {
        if (cfg == null || !cfg.enabled() || e == null) {
            return false;
        }
        if (!cfg.coversType(e.type())) {
            return false;
        }
        int minute = LocalTime.now().getHour() * 60 + LocalTime.now().getMinute();
        return !cfg.inQuietWindow(minute);
    }

    // ===================== Webhook =====================

    private String sendWebhookWithRetry(NotificationSettings cfg, EventStore.AlarmEvent e) {
        return sendWithRetry(() -> sendWebhookOnce(cfg, webhookPayload(e)));
    }

    /** 事件 → Webhook JSON 载荷。 */
    Map<String, Object> webhookPayload(EventStore.AlarmEvent e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", e.id());
        payload.put("did", e.did());
        payload.put("deviceName", e.deviceName());
        payload.put("type", e.type());
        payload.put("level", e.level());
        payload.put("title", e.title());
        payload.put("score", e.score());
        payload.put("createdAt", e.createdAt());
        payload.put("snapshot", e.snapshot());
        return payload;
    }

    /** 单次 Webhook 投递；成功返回 null，失败返回错误描述。 */
    public String sendWebhookOnce(NotificationSettings cfg, Map<String, Object> payload) {
        try {
            byte[] body = mapper.writeValueAsBytes(payload);

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(cfg.webhookUrl()))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "aiNVR-notify/2.1")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (!cfg.webhookSecret().isBlank()) {
                // v2.1.2 品牌统一为 X-aiNVR-Signature；旧名 X-invr-Signature 继续随附一个版本周期，
                // 已按旧头名配置验签的接收端不受影响，后续可移除旧头
                String sig = "sha256=" + hmacSha256Hex(cfg.webhookSecret(), body);
                rb.header("X-aiNVR-Signature", sig);
                rb.header("X-invr-Signature", sig);
            }
            // 每次投递新建客户端：通知低频，开销可忽略；避免复用已被服务端关闭的
            // 连接池连接（偶发 "header parser received no bytes" 假失败）
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(8))
                    .build();
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return null;
            }
            return "HTTP " + resp.statusCode();
        } catch (Exception ex) {
            return ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
    }

    static String hmacSha256Hex(String secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    // ===================== 重试 =====================

    interface Channel {
        /** 返回 null=成功，非 null=失败原因。 */
        String attempt();
    }

    private String sendWithRetry(Channel ch) {
        long[] delays = backoff;
        String last = null;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            if (delays[i] > 0) {
                try {
                    Thread.sleep(delays[i]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return last == null ? "interrupted" : last;
                }
            }
            last = ch.attempt();
            if (last == null) {
                return null;
            }
        }
        return last;
    }

    /** 注入重试间隔（测试用，长度须 ≥ MAX_ATTEMPTS）。 */
    void setBackoffForTest(long[] delays) {
        this.backoff = delays;
    }

    // ===================== 文案 =====================

    private String renderBody(EventStore.AlarmEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append("设备：").append(e.deviceName()).append("（").append(e.did()).append("）\n");
        sb.append("事件：").append(e.title()).append("\n");
        sb.append("类型：").append(e.type()).append("  级别：").append(e.level()).append("\n");
        if (e.score() > 0) {
            sb.append("变化评分：").append(String.format("%.3f", e.score())).append("\n");
        }
        sb.append("时间：").append(java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(e.createdAt()),
                        java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n");
        if (!e.snapshot().isBlank()) {
            sb.append("快照：/appdata/invr 事件页可查看\n");
        }
        sb.append("\n—— invr 网络录像服务");
        return sb.toString();
    }
}
