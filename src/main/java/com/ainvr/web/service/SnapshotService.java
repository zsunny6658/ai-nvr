package com.ainvr.web.service;

import com.ainvr.codec.SnapshotDecoder;
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

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 摄像头实时截帧服务：对外提供“按 DID 取当前一帧 JPEG”。
 *
 * <p>每次请求创建一个一次性 {@link SnapshotDecoder} 作为 {@link StreamSink} 订阅全局
 * {@link StreamHub}——与录像 / RTSP / HLS 复用同一条摄像头上游，不额外建连；新订阅会立即
 * 收到 Hub 回放的当前 GOP（以 IDR 起始），热态下通常数十毫秒即可出帧。解码线程为该订阅
 * 专属的派发线程，请求线程仅等待结果。首帧到手（或超时）后立即退订并释放全部 native 资源。</p>
 *
 * <p>冷启动（该摄像头当前无任何消费者）需先完成 CS2 建连与首个 IDR，故超时上限放宽到
 * {@value #CAPTURE_TIMEOUT_MS} ms；另有信号量限制并发截帧路数，避免异常调用堆积 native 解码器。</p>
 *
 * <p>v2.3.1 新增 {@link #captureCached(String)}：供网页「摄像头卡顶快照」这类周期轮询场景使用。
 * 缓存新鲜期内直接返回上一帧（零等待、零建连）；过期后立即返回旧帧并交由单线程后台串行刷新，
 * 多路摄像头逐个更新，不会同时拉起多路订阅。首次访问（无缓存）仍走同步截帧。</p>
 *
 * <p>v2.3.1 追加后台预热（对齐多宫格「常驻缓存帧秒回」体验）：启动后对全部在线摄像头各截一帧
 * 预热缓存；此后凡是被网页请求过的摄像头（10 分钟热度窗口）由预热线程每 25 秒串行保鲜一轮，
 * 页面打开期间缓存帧始终新鲜——摄像头卡顶快照与多宫格一样秒出。休眠 / 局域网不可达的摄像头
 * 通过 {@link DeviceProbe} 跳过，不为拉帧唤醒设备。</p>
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    /** 冷启动建连 + 等关键帧的整体上限，超时返回 503 让调用方重试。 */
    private static final long CAPTURE_TIMEOUT_MS = 20_000;
    /** 同时进行的截帧解码上限（每个解码器都是独立 native 上下文）。 */
    private static final int MAX_INFLIGHT = 8;
    /** v2.3.1 缓存帧新鲜期：期内直接回缓存，网页卡顶快照零等待。 */
    private static final long CACHE_FRESH_MS = 20_000;
    /** v2.3.1 预热扫描周期：单线程串行逐台保鲜，scheduleWithFixedDelay 自 pacing 不重叠。 */
    private static final long WARM_PERIOD_MS = 25_000;
    /** v2.3.1 「近期被看过」窗口：窗口内的摄像头保持后台保鲜，超窗即停止预热（避免无人观看时空转）。 */
    private static final long HOT_TTL_MS = 10 * 60_000L;
    /** 缓存无主回收期：非热门且超过该时长的缓存帧在扫描中清掉，后台完全静默。 */
    private static final long CACHE_IDLE_MS = HOT_TTL_MS;

    private final StreamHub hub;
    private final CameraConnector connector;
    private final DeviceProbe deviceProbe;
    private final DeviceCatalog catalog;
    private final Semaphore inflight = new Semaphore(MAX_INFLIGHT, true);
    /** v2.3.1 按 DID 缓存最近一帧（网页卡顶快照走缓存，避免每次冷启动建连）。 */
    private final Map<String, CachedFrame> cache = new ConcurrentHashMap<>();
    /** v2.3.1 近期被请求过的 DID → 最后请求时间：预热只覆盖真正有人在看的摄像头。 */
    private final Map<String, Long> hot = new ConcurrentHashMap<>();
    /** 后台刷新专用单线程：多路摄像头到期逐个串行刷新，避免同时订阅 CS2 通道造成抖动。 */
    private final java.util.concurrent.ExecutorService refreshPool =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "invr-snap-refresh");
                t.setDaemon(true);
                return t;
            });
    /** v2.3.1 预热扫描线程：与 refreshPool 分开，避免预热长任务阻塞即时刷新。 */
    private final ScheduledExecutorService warmer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "invr-snap-warmer");
                t.setDaemon(true);
                return t;
            });

    private static final class CachedFrame {
        final byte[] jpeg;
        final long capturedAt;
        volatile boolean refreshing;

        CachedFrame(byte[] jpeg, long capturedAt) {
            this.jpeg = jpeg;
            this.capturedAt = capturedAt;
        }
    }

    public SnapshotService(StreamHub hub, CameraConnector connector,
                           DeviceProbe deviceProbe, DeviceCatalog catalog) {
        this.hub = hub;
        this.connector = connector;
        this.deviceProbe = deviceProbe;
        this.catalog = catalog;
        // v2.3.1 启动预热：20s / 90s 各扫一遍全部在线摄像头（设备目录可能晚到，双保险），
        // 让用户首次打开摄像头页时缓存已就绪、卡顶快照秒出
        warmer.schedule(this::warmAllPass, 20, TimeUnit.SECONDS);
        warmer.schedule(this::warmAllPass, 90, TimeUnit.SECONDS);
        // 此后按热度窗口周期保鲜
        warmer.scheduleWithFixedDelay(this::warmHotPass, WARM_PERIOD_MS, WARM_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 带缓存的截帧（v2.3.1）：新鲜期内直接回缓存帧；过期回旧帧并后台串行刷新；
     * 无缓存时同步截帧。设备离线等业务错误（{@link ApiException}）直接抛出。
     */
    public byte[] captureCached(String did) {
        hot.put(did, System.currentTimeMillis()); // v2.3.1 标记热度，预热线程据此保鲜
        CachedFrame f = cache.get(did);
        long now = System.currentTimeMillis();
        if (f != null) {
            if (now - f.capturedAt > CACHE_FRESH_MS && !f.refreshing) {
                f.refreshing = true;
                refreshPool.execute(() -> {
                    try {
                        byte[] jpeg = capture(did);
                        cache.put(did, new CachedFrame(jpeg, System.currentTimeMillis()));
                    } catch (Exception e) {
                        // 刷新失败保留旧帧，下轮重试；设备离线时持续保留最后一帧更有价值
                        log.debug("[{}] 快照后台刷新失败（保留旧帧）：{}", did, e.getMessage());
                    } finally {
                        CachedFrame cur = cache.get(did);
                        if (cur != null) {
                            cur.refreshing = false;
                        }
                    }
                });
            }
            return f.jpeg;
        }
        byte[] jpeg = capture(did);
        cache.put(did, new CachedFrame(jpeg, System.currentTimeMillis()));
        return jpeg;
    }

    /**
     * 截取指定摄像头当前画面一帧。
     *
     * @param did 摄像头 DID
     * @return 完整 JPEG 字节（image/jpeg）
     * @throws ApiException 设备不存在/离线、并发过多、超时或解码失败
     */
    public byte[] capture(String did) {
        CameraDevice device = connector.findDevice(did);
        if (device == null) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        if (!device.online()) {
            throw new ApiException(503, "摄像头当前不在线，无法截取画面");
        }
        if (!inflight.tryAcquire()) {
            throw new ApiException(503, "当前截帧请求过多，请稍后再试");
        }

        SnapshotDecoder decoder = new SnapshotDecoder();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        StreamSink sink = new StreamSink() {
            @Override
            public void onReset(int generation) {
                try {
                    decoder.reset();
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            }

            @Override
            public void onPacket(MissPacket packet, int generation) {
                if (result.isDone()) {
                    return;
                }
                try {
                    byte[] jpeg = decoder.accept(packet);
                    if (jpeg != null) {
                        result.complete(jpeg);
                    }
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            }
        };

        // 订阅即触发上游拉起（或复用现有上游），并同步回放当前 GOP
        hub.subscribe(did, device.model(), connector.openerFor(device), sink);
        try {
            return result.get(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(503, "截取摄像头画面超时，请确认设备在线后重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException api) {
                throw api;
            }
            String msg = cause == null ? e.toString() : cause.getMessage();
            throw new ApiException(500, "截帧失败：" + (msg == null ? cause.toString() : msg));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(500, "截帧请求被中断");
        } finally {
            hub.unsubscribe(did, sink);
            decoder.close();
            inflight.release();
        }
    }

    @PreDestroy
    public void shutdown() {
        // 解码器随请求结束自行释放；这里仅留统一关闭钩子，避免容器销毁时残留等待
        log.debug("SnapshotService 关闭，在途请求 {} 个", MAX_INFLIGHT - inflight.availablePermits());
        warmer.shutdownNow();
        refreshPool.shutdownNow();
        cache.clear();
        hot.clear();
    }

    /* ---------- v2.3.1 后台预热：缓存帧始终新鲜，摄像头卡顶快照对齐多宫格秒开 ---------- */

    /** 启动预热：对全部在线摄像头各截一帧（休眠 / 不可达跳过）。目录晚到时由第二轮兜底。 */
    void warmAllPass() {
        try {
            for (CameraDevice d : catalog.all()) {
                if (d == null || !d.online()) {
                    continue;
                }
                warmOne(d.did());
            }
        } catch (Throwable t) {
            log.debug("快照启动预热异常（下轮重试）：{}", t.toString());
        }
    }

    /** 热度保鲜：只刷近期被请求过的摄像头与尚有缓存帧的摄像头，超窗即完全静默。 */
    void warmHotPass() {
        try {
            long now = System.currentTimeMillis();
            hot.entrySet().removeIf(e -> now - e.getValue() > HOT_TTL_MS);
            cache.entrySet().removeIf(e -> {
                boolean staleIdle = !hot.containsKey(e.getKey())
                        && now - e.getValue().capturedAt > CACHE_IDLE_MS;
                return staleIdle; // 没人看且过期的缓存清掉，后台归零
            });
            Set<String> targets = new HashSet<>(hot.keySet());
            targets.addAll(cache.keySet());
            for (String did : targets) {
                warmOne(did);
            }
        } catch (Throwable t) {
            log.debug("快照预热扫描异常（下轮重试）：{}", t.toString());
        }
    }

    /** 单台保鲜：缓存仍新鲜则跳过；休眠 / 局域网不可达不拉连接；失败保留旧帧。 */
    private void warmOne(String did) {
        CachedFrame f = cache.get(did);
        if (f != null && System.currentTimeMillis() - f.capturedAt <= CACHE_FRESH_MS) {
            return;
        }
        try {
            if (Boolean.FALSE.equals(deviceProbe.reachable(did))) {
                return; // 休眠 / 局域网不可达：不为拉快照唤醒设备，保留旧帧
            }
            byte[] jpeg = capture(did);
            cache.put(did, new CachedFrame(jpeg, System.currentTimeMillis()));
        } catch (Exception e) {
            log.debug("[{}] 快照预热刷新失败（保留旧帧）：{}", did, e.getMessage());
        }
    }
}
