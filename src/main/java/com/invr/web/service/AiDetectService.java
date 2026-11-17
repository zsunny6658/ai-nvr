package com.invr.web.service;

import com.invr.store.AppSettingsStore;
import com.invr.store.EventStore;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * v2.1.0 A2 本地 AI 检测：对事件快照做本地推理，把 motion 事件细化为
 * person / vehicle / pet（置信度不足时保持 motion 不变）。
 *
 * <p>模型：SSD-MobileNetV1（ONNX Model Zoo，<strong>MIT 许可</strong>，Apache-2.0 兼容，
 * 已按 aiNVR 合规决策避开 YOLO 原版的 AGPL）。运行时：ONNX Runtime Java（MIT），
 * CPU EP，输入 uint8 NHWC（免归一化），输出已含 NMS。</p>
 *
 * <p>接入方式：以 {@link EventStore#addListener} 追加式监听 motion 新增事件（带快照者），
 * 入单线程队列异步分类，完成后 {@link EventStore#refine} 原位更新事件类型与检测框——
 * 不阻塞事件写入，也绝不抛异常出监听器。合并事件（repeat）不走 add 路径，天然不重复分类。</p>
 *
 * <p>算力保护：并发分类用 {@link Semaphore} 限 1、队列积压超过 {@link #MAX_PENDING}
 * 时丢弃最旧之外的请求（AI 是尽力而为的增强，不能拖垮录像主业务）。</p>
 */
@Service
public class AiDetectService {

    private static final Logger log = LoggerFactory.getLogger(AiDetectService.class);

    /** 模型输入边长（SSD-MobileNetV1 默认 300x300；模型支持动态尺寸，缩小仅为控算力）。 */
    private static final int INPUT_SIZE = 300;
    /** 单事件最多输出的检测框数。 */
    private static final int MAX_REPORT = 5;
    /** 待分类队列上限：超出后直接放弃（事件仍是 motion，不影响原有功能）。 */
    private static final int MAX_PENDING = 16;

    /** COCO 类别（TF 1-based）→ aiNVR 事件类型。 */
    private static final Map<Integer, String> CLASS_TO_TYPE = Map.ofEntries(
            Map.entry(1, "person"),
            Map.entry(2, "vehicle"), Map.entry(3, "vehicle"), Map.entry(4, "vehicle"),
            Map.entry(6, "vehicle"), Map.entry(7, "vehicle"), Map.entry(8, "vehicle"), Map.entry(9, "vehicle"),
            Map.entry(16, "pet"), Map.entry(17, "pet"), Map.entry(18, "pet"), Map.entry(19, "pet"),
            Map.entry(20, "pet"), Map.entry(21, "pet"), Map.entry(23, "pet"), Map.entry(24, "pet"));

    /** COCO 类别名（1-based 常用段，仅用于检测框展示）。 */
    private static final Map<Integer, String> CLASS_LABEL = Map.ofEntries(
            Map.entry(1, "person"), Map.entry(2, "bicycle"), Map.entry(3, "car"),
            Map.entry(4, "motorcycle"), Map.entry(6, "bus"), Map.entry(7, "train"),
            Map.entry(8, "truck"), Map.entry(9, "boat"), Map.entry(16, "bird"),
            Map.entry(17, "cat"), Map.entry(18, "dog"), Map.entry(19, "horse"),
            Map.entry(20, "sheep"), Map.entry(21, "cow"), Map.entry(23, "bear"), Map.entry(24, "zebra"));

    private final AppSettingsStore settingsStore;
    private final EventStore events;

    private final Object sessionLock = new Object();
    private volatile OrtSession session;
    private volatile OrtEnvironment env;
    /** 模型加载失败标志：加载失败后不再反复尝试（下次重启再试），避免每事件刷错误日志。 */
    private volatile boolean modelUnavailable;
    private final Semaphore inflight = new Semaphore(1);
    private final java.util.concurrent.ThreadPoolExecutor worker;
    private volatile boolean stopped;

    public AiDetectService(AppSettingsStore settingsStore, EventStore events) {
        this.settingsStore = settingsStore;
        this.events = events;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "ai-detect");
            t.setDaemon(true);
            return t;
        };
        this.worker = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), tf);
    }

    @PostConstruct
    void register() {
        events.addListener(this::onEvent);
        log.info("本地 AI 检测已接入（SSD-MobileNetV1，事件快照分类）");
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        stopped = true;
        worker.shutdown();
        worker.awaitTermination(5, TimeUnit.SECONDS);
        synchronized (sessionLock) {
            if (session != null) {
                try {
                    session.close();
                } catch (ai.onnxruntime.OrtException e) {
                    log.warn("AI 会话关闭失败：{}", e.toString());
                }
                session = null;
            }
        }
    }

    /** 新增事件入口：只对带快照的 motion 事件分类；任何异常吞掉。 */
    void onEvent(EventStore.AlarmEvent e) {
        try {
            if (stopped || e == null || !"motion".equals(e.type())
                    || e.snapshot() == null || e.snapshot().isEmpty()) {
                return;
            }
            if (!settingsStore.aiSettings().enabled()) {
                return;
            }
            if (worker.getQueue().size() >= MAX_PENDING) {
                log.debug("AI 队列已满，跳过事件 {}", e.id());
                return;
            }
            worker.execute(() -> classifyAndRefine(e.id()));
        } catch (RejectedExecutionException ignore) {
            // 关闭中的竞态，忽略
        } catch (RuntimeException ex) {
            log.warn("AI 分类调度失败 eventId={}: {}", e == null ? -1 : e.id(), ex.toString());
        }
    }

    /** 工作线程：读快照 → 推理 → 精化事件类型。 */
    void classifyAndRefine(long eventId) {
        try {
            if (!inflight.tryAcquire()) {
                return;
            }
            try {
                byte[] jpeg = events.snapshot(eventId);
                if (jpeg == null || jpeg.length == 0) {
                    return;
                }
                List<Map<String, Object>> detections = detect(jpeg);
                if (detections.isEmpty()) {
                    return; // 没有高置信度目标，事件保持 motion
                }
                String best = detections.get(0).get("type") instanceof String t ? t : "other";
                events.refine(eventId, best, detections);
                log.info("AI 精化事件 {} -> {} ({} 个目标)", eventId, best, detections.size());
            } finally {
                inflight.release();
            }
        } catch (Exception ex) {
            log.warn("AI 分类失败 eventId={}: {}", eventId, ex.toString());
        }
    }

    /**
     * 同步检测（测试与未来接口直调）。返回按分数降序的检测框（最多 {@link #MAX_REPORT} 个），
     * 每项：{label, type, score, box:[top,left,bottom,right](归一化)}。
     */
    public List<Map<String, Object>> detect(byte[] jpeg) throws IOException, ai.onnxruntime.OrtException {
        AppSettingsStore.AiSettings cfg = settingsStore.aiSettings();
        if (!cfg.enabled() || jpeg == null || jpeg.length == 0) {
            return List.of();
        }
        OrtSession s = session();
        if (s == null) {
            return List.of();
        }
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
        if (img == null) {
            return List.of();
        }
        BufferedImage resized = resize(img, INPUT_SIZE, INPUT_SIZE);
        float minScore = (float) cfg.minScore();
        List<Map<String, Object>> out = new ArrayList<>();
        try (OnnxTensor input = toTensor(resized);
             OrtSession.Result res = s.run(Map.of("image_tensor:0", input))) {
            // 模型输出（按模型卡顺序，这里按名取，稳定）：num_detections / boxes / scores / classes
            float numDet = ((OnnxTensor) res.get("num_detections:0").orElseThrow()).getFloatBuffer().get(0);
            float[] boxes = toArray((OnnxTensor) res.get("detection_boxes:0").orElseThrow());
            float[] scores = toArray((OnnxTensor) res.get("detection_scores:0").orElseThrow());
            float[] classes = toArray((OnnxTensor) res.get("detection_classes:0").orElseThrow());
            int n = Math.min((int) numDet, scores.length);
            for (int i = 0; i < n; i++) {
                float score = scores[i];
                if (score < minScore) {
                    break; // 输出按分数降序
                }
                int cls = Math.round(classes[i]);
                String type = CLASS_TO_TYPE.get(cls);
                if (type == null) {
                    continue; // 只关注人/车/宠物
                }
                int b = i * 4;
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("label", CLASS_LABEL.getOrDefault(cls, "c" + cls));
                d.put("type", type);
                d.put("score", Math.round(score * 1000) / 1000.0);
                d.put("box", List.of(round4(boxes[b]), round4(boxes[b + 1]),
                        round4(boxes[b + 2]), round4(boxes[b + 3])));
                out.add(d);
                if (out.size() >= MAX_REPORT) {
                    break;
                }
            }
        }
        out.sort((a, b) -> Double.compare((Double) b.get("score"), (Double) a.get("score")));
        return out;
    }

    /** 服务状态（设置页展示）：enabled/minScore/模型是否可用/队列长度。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        AppSettingsStore.AiSettings cfg = settingsStore.aiSettings();
        m.put("enabled", cfg.enabled());
        m.put("minScore", cfg.minScore());
        m.put("modelAvailable", !modelUnavailable);
        m.put("pending", worker.getQueue().size());
        return m;
    }

    /* ===================== 内部 ===================== */

    /** 惰性加载模型；数据目录 models/ssd_mobilenet_v1_10.onnx 优先（便于换模型），回落 classpath 内置。 */
    private OrtSession session() {
        OrtSession s = session;
        if (s != null) {
            return s;
        }
        if (modelUnavailable) {
            return null;
        }
        synchronized (sessionLock) {
            if (session != null) {
                return session;
            }
            try {
                byte[] modelBytes = loadModelBytes();
                if (env == null) {
                    env = OrtEnvironment.getEnvironment();
                }
                session = env.createSession(modelBytes, new OrtSession.SessionOptions());
                log.info("AI 模型已加载（SSD-MobileNetV1，{} KB）", modelBytes.length / 1024);
                return session;
            } catch (Throwable t) {
                modelUnavailable = true;
                log.error("AI 模型加载失败，本地检测停用（重启应用重试）：{}", t.toString());
                return null;
            }
        }
    }

    private byte[] loadModelBytes() throws IOException {
        Path custom = com.invr.store.DataDir.get().resolve("models").resolve("ssd_mobilenet_v1_10.onnx");
        if (Files.isRegularFile(custom)) {
            return Files.readAllBytes(custom);
        }
        try (InputStream in = AiDetectService.class.getResourceAsStream("/models/ssd_mobilenet_v1_10.onnx")) {
            if (in == null) {
                throw new IOException("内置模型缺失");
            }
            return in.readAllBytes();
        }
    }

    /** 缩放到输入尺寸（直接拉伸，SSD 对纵横比不敏感；相机快照通常已是 16:9）。 */
    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /** HWC uint8 → OnnxTensor，形状 [1,h,w,3]。 */
    private static OnnxTensor toTensor(BufferedImage img) throws ai.onnxruntime.OrtException {
        int h = img.getHeight(), w = img.getWidth();
        byte[] rgb = new byte[h * w * 3];
        int[] pix = img.getRGB(0, 0, w, h, null, 0, w);
        int i = 0;
        for (int p : pix) {
            rgb[i++] = (byte) ((p >> 16) & 0xFF);
            rgb[i++] = (byte) ((p >> 8) & 0xFF);
            rgb[i++] = (byte) (p & 0xFF);
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(rgb.length).order(ByteOrder.nativeOrder());
        buf.put(rgb);
        buf.rewind();
        return OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), buf, new long[]{1, h, w, 3},
                ai.onnxruntime.OnnxJavaType.UINT8);
    }

    /** 统一按 FloatBuffer 平铺读取（num/boxes/scores/classes 全为 float32，维度各异）。 */
    private static float[] toArray(OnnxTensor t) {
        java.nio.FloatBuffer fb = t.getFloatBuffer();
        float[] out = new float[fb.remaining()];
        fb.get(out);
        return out;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
