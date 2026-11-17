package com.ainvr.web.service;

import com.ainvr.codec.LiveFrameDecoder;
import com.ainvr.hub.CameraConnector;
import com.ainvr.hub.StreamHub;
import com.ainvr.hub.StreamSink;
import com.ainvr.miss.MissPacket;
import com.ainvr.model.CameraDevice;
import com.ainvr.web.api.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 「兼容画面模式」帧源：为每台摄像头维持一条常驻订阅与一个常驻解码器，缓存最新一帧 JPEG。
 *
 * <p>为什么不用逐次 {@link SnapshotService#capture(String)}：后者每次请求都新建解码器并
 * 立刻退订，而 {@link StreamHub#unsubscribe} 在无消费者时会直接断开摄像头上游。若前端按
 * 500ms 轮询快照，就会变成每半秒重连一次 CS2 通道，正好触发历史上「命令通道溢出 / 15 秒
 * 自激重连」那类故障。因此这里保持订阅，空闲一段时间后才整体回收。</p>
 *
 * <p>节流与缩放由 {@link LiveFrameDecoder} 完成（默认 ≤4fps、宽 ≤640），解码后输出 JPEG，
 * 浏览器只需 {@code <img>} 即可显示——不依赖 MSE、不依赖 HEVC 硬解，飞牛 App 的 Android
 * WebView、iOS WKWebView、PC 浏览器都能出画面。代价是无声音、帧率较低且解码消耗 CPU，
 * 因此仅作为 HLS 不可用时的兜底，并按需启停。</p>
 */
@Service
public class LiveFrameService {

    private static final Logger log = LoggerFactory.getLogger(LiveFrameService.class);

    /** JPEG 编码最小间隔（4fps）。 */
    private static final long FRAME_INTERVAL_MS = 250L;
    /** 输出画面最大宽度，等比缩小以省带宽与编码开销。 */
    private static final int MAX_WIDTH = 640;
    /** 冷启动等首帧上限。 */
    private static final long FIRST_FRAME_TIMEOUT_MS = 20_000L;
    /** 无人取帧超过该时长即回收订阅与解码器。 */
    private static final long IDLE_RETIRE_MS = 20_000L;
    private static final long JANITOR_PERIOD_MS = 5_000L;

    private final StreamHub hub;
    private final CameraConnector connector;
    private final Map<String, Source> sources = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "invr-liveframe-janitor");
                t.setDaemon(true);
                return t;
            });

    public LiveFrameService(StreamHub hub, CameraConnector connector) {
        this.hub = hub;
        this.connector = connector;
        janitor.scheduleAtFixedRate(this::retireIdle, JANITOR_PERIOD_MS,
                JANITOR_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 取该摄像头当前最新画面。首次调用会建立订阅并等待首帧；之后直接返回缓存帧。
     *
     * @throws ApiException 设备不存在/离线、等待首帧超时或解码失败
     */
    public byte[] frame(String did) {
        Source s = sources.computeIfAbsent(did, this::create);
        s.lastAccess = System.currentTimeMillis();
        byte[] jpeg = s.latest.get();
        if (jpeg != null) {
            return jpeg;
        }
        try {
            return s.first.get(FIRST_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            retire(did, s);
            throw new ApiException(503, "兼容画面取帧超时，请确认摄像头在线后重试");
        } catch (ExecutionException e) {
            retire(did, s);
            Throwable cause = e.getCause();
            if (cause instanceof ApiException api) {
                throw api;
            }
            throw new ApiException(500, "兼容画面取帧失败："
                    + (cause == null ? e.toString() : cause.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "兼容画面取帧被中断");
        }
    }

    /** 当前是否有活跃帧源（供诊断/测试观察）。 */
    public int activeSources() {
        return sources.size();
    }

    private Source create(String did) {
        CameraDevice device = connector.findDevice(did);
        if (device == null) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        if (!device.online()) {
            throw new ApiException(503, "摄像头当前不在线，无法获取兼容画面");
        }

        Source s = new Source(did, new LiveFrameDecoder(FRAME_INTERVAL_MS, MAX_WIDTH));
        s.lastAccess = System.currentTimeMillis();
        StreamSink sink = new StreamSink() {
            @Override
            public void onReset(int generation) {
                try {
                    s.decoder.reset();
                } catch (Throwable t) {
                    s.first.completeExceptionally(t);
                }
            }

            @Override
            public void onPacket(MissPacket packet, int generation) {
                if (s.retired.get()) {
                    return;
                }
                try {
                    byte[] jpeg = s.decoder.accept(packet);
                    if (jpeg != null) {
                        s.latest.set(jpeg);
                        if (!s.first.isDone()) {
                            s.first.complete(jpeg);
                        }
                    }
                } catch (Throwable t) {
                    if (!s.first.isDone()) {
                        s.first.completeExceptionally(t);
                    }
                    log.warn("[{}] 兼容画面解码异常（已跳过）：{}", did, t.toString());
                }
            }
        };
        s.sink = sink;
        // subscribe 会同步回放当前 GOP，首个包即 IDR，通常当次调用内就能拿到首帧
        hub.subscribe(did, device.model(), connector.openerFor(device), sink);
        log.info("兼容画面帧源已建立 did={}（活跃帧源={}）", did, sources.size() + 1);
        return s;
    }

    private void retireIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            if (now - e.getValue().lastAccess > IDLE_RETIRE_MS) {
                retire(e.getKey(), e.getValue());
            }
        }
    }

    private void retire(String did, Source s) {
        if (s == null || !s.retired.compareAndSet(false, true)) {
            return;
        }
        try {
            hub.unsubscribe(did, s.sink);
        } catch (Throwable t) {
            log.warn("[{}] 兼容画面退订异常：{}", did, t.toString());
        }
        s.decoder.close();
        sources.remove(did, s);
        log.info("兼容画面帧源已回收 did={}", did);
    }

    @PreDestroy
    public void shutdown() {
        janitor.shutdownNow();
        for (Map.Entry<String, Source> e : sources.entrySet()) {
            retire(e.getKey(), e.getValue());
        }
    }

    private static final class Source {
        final String did;
        final LiveFrameDecoder decoder;
        final AtomicReference<byte[]> latest = new AtomicReference<>();
        final CompletableFuture<byte[]> first = new CompletableFuture<>();
        final java.util.concurrent.atomic.AtomicBoolean retired = new java.util.concurrent.atomic.AtomicBoolean(false);
        volatile StreamSink sink;
        volatile long lastAccess;

        Source(String did, LiveFrameDecoder decoder) {
            this.did = did;
            this.decoder = decoder;
        }
    }
}
