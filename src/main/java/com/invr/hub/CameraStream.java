package com.invr.hub;

import com.invr.miss.MissClient;
import com.invr.miss.MissPacket;
import com.invr.miss.NalUnits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 单台摄像头的唯一上游取流会话（单通道复用的核心）。
 *
 * <p>对同一台设备，全局只允许存在一个 {@code CameraStream}，也就只占用一条 CS2/MISS 上游连接；
 * 录像、RTSP、CLI 落盘等都作为 {@link StreamSink} 订阅到这里，由唯一的上游取流线程扇出。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li><b>引用计数</b>：第一个订阅者到来时拉起上游，最后一个离开时停止（VideoStop/断连），不空占通道。</li>
 *   <li><b>自动重连</b>：读包异常 / 15s 无媒体包即判定断流，重新 miss_get_vendor→握手→登录→VideoStart，
 *       退避 1/2/4/8/16/30s 封顶，期间无限重试。</li>
 *   <li><b>GOP 缓存</b>：缓存最近一个关键帧（含 VPS/SPS/PPS/IDR）以来的视频包，新订阅者立即回放，做到秒开。</li>
 *   <li><b>隔离慢消费者</b>：每个订阅者独立有界队列 + 独立派发线程，任何消费者阻塞都不会拖垮上游取流。</li>
 * </ul>
 */
public final class CameraStream {

    private static final Logger log = LoggerFactory.getLogger(CameraStream.class);

    /** 单个订阅者的有界队列容量（包）。录像写盘很快，正常不会触顶；满则丢该包并计数告警。 */
    private static final int SUBSCRIBER_QUEUE_CAPACITY = 1000;
    /** GOP 缓存硬上限，防止极端情况下内存膨胀（3.5K 一个 GOP 通常远小于此）。 */
    private static final int GOP_MAX_BYTES = 16 * 1024 * 1024;
    /**
     * 重连退避：首跳 200ms（对齐 go2rtc——CS2 上游约每 110s 固有断一次，设备接受立即重连），
     * 仅在连续失败时才翻倍退避并封顶 30s。
     */
    private static final long[] BACKOFF_MS = {200, 500, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000};

    private final String did;
    private final String model;
    private final MissOpener opener;

    private final Object lifecycleLock = new Object();
    /** 上游分发锁：串行化"取流广播 / 重连 reset / 新订阅回放 GOP"，保证不重不漏。 */
    private final Object deliverLock = new Object();

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final List<MissPacket> gop = new ArrayList<>();
    private long gopBytes;

    private volatile boolean running;
    private Thread upstreamThread;
    private volatile MissClient current;
    private volatile int generation;

    /** 退避等待锁：设备重新上线 / 清晰度变更时可立即打断长达 5 分钟的熔断等待。 */
    private final Object backoffLock = new Object();
    /** 由 {@link #wakeup} 置位，runUpstream 读到后清零退避并立即重连。 */
    private volatile boolean wakeupRequested;
    /** 当前上游是否收到过媒体包（云台控制要求会话可用）。 */
    private volatile boolean healthy;
    private volatile long lastFrameAt;
    private volatile long lastErrorAt;
    /** 连续「一个媒体包都没收到」的连接次数，用于重连熔断。 */
    private volatile int consecutiveNoFrame;

    public CameraStream(String did, String model, MissOpener opener) {
        this.did = did;
        this.model = model;
        this.opener = opener;
    }

    public String did() {
        return did;
    }

    public String model() {
        return model;
    }

    public int generation() {
        return generation;
    }

    public int subscriberCount() {
        return subscriptions.size();
    }

    /**
     * 订阅该摄像头。首个订阅会启动唯一上游；新订阅立即获得当前 GOP 回放。
     */
    public void subscribe(StreamSink sink) {
        if (sink == null) {
            throw new IllegalArgumentException("sink 不能为空");
        }
        boolean needStart;
        Subscription sub = new Subscription(sink);
        synchronized (lifecycleLock) {
            needStart = !running;
            if (needStart) {
                running = true;
                startUpstream();
            }
            subscriptions.add(sub);
            synchronized (deliverLock) {
                sub.start();
                // 先告知当前代数（未连接时为 0，连接成功后会再 reset 一次），再回放当前 GOP
                sub.dispatchReset(generation);
                for (MissPacket p : gop) {
                    sub.offerPacket(new PacketEvent(generation, p));
                }
            }
        }
        log.info("摄像头 {} 新增订阅者（当前 {} 个，{}）", did, subscriptions.size(),
                needStart ? "已请求启动唯一上游" : "复用现有上游");
    }

    /**
     * 取消订阅；最后一个取消时停止并断开唯一上游，释放通道。
     */
    public void unsubscribe(StreamSink sink) {
        boolean needStop = false;
        Thread toJoin = null;
        Subscription removed = null;
        synchronized (lifecycleLock) {
            Subscription found = null;
            for (Subscription s : subscriptions) {
                if (s.sink == sink) {
                    found = s;
                    break;
                }
            }
            if (found == null) {
                return;
            }
            subscriptions.remove(found);
            found.close();
            removed = found;
            log.info("摄像头 {} 移除订阅者（剩余 {} 个）", did, subscriptions.size());
            if (subscriptions.isEmpty() && running) {
                running = false;
                needStop = true;
                toJoin = upstreamThread;
                if (upstreamThread != null) {
                    upstreamThread.interrupt();
                }
                MissClient c = current;
                current = null;
                closeQuietly(c);
            }
        }
        if (needStop && toJoin != null) {
            try {
                toJoin.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("摄像头 {} 已无订阅者，唯一上游通道已释放", did);
        }
        // v1.2.15：退订后等分发线程退出再让调用方 purge()，避免与在途回调并发释放原生资源
        if (removed != null) {
            removed.awaitStop();
        }
    }

    /** 彻底停止（服务关闭时调用）。 */
    public void shutdown() {
        List<Subscription> closing;
        synchronized (lifecycleLock) {
            closing = new ArrayList<>(subscriptions);
            for (Subscription s : closing) {
                s.close();
            }
            subscriptions.clear();
            if (running) {
                running = false;
                if (upstreamThread != null) {
                    upstreamThread.interrupt();
                }
                MissClient c = current;
                current = null;
                closeQuietly(c);
            }
        }
        for (Subscription s : closing) {
            s.awaitStop();
        }
    }

    // ------------------------------------------------------------------
    // 唯一上游取流线程
    // ------------------------------------------------------------------

    private void startUpstream() {
        generation = 0;
        upstreamThread = new Thread(this::runUpstream, "cs2-upstream-" + did);
        upstreamThread.setDaemon(true);
        upstreamThread.start();
    }

    private void runUpstream() {
        int backoffIndex = 0;
        while (running) {
            MissClient client = null;
            int gen = 0;
            boolean gotFrame = false;
            try {
                log.info("[{}] 正在建立上游取流连接（重新协商+CS2+MISS+VideoStart）...", did);
                client = opener.open();
                current = client;
                synchronized (deliverLock) {
                    gen = ++generation;
                    gop.clear();
                    gopBytes = 0;
                    for (Subscription s : subscriptions) {
                        s.dispatchReset(gen);
                    }
                }
                log.info("[{}] 上游已连接并开始推流（generation={}，{}）", did, gen, client.protocol());
                backoffIndex = 0;
                healthy = false;

                while (running) {
                    MissPacket pkt = client.readPacket();
                    if (pkt == null) {
                        continue;
                    }
                    if (!gotFrame) {
                        healthy = true;
                        log.info("[{}] 首帧已到达，上游链路健康（generation={}）", did, gen);
                    }
                    gotFrame = true;
                    lastFrameAt = System.currentTimeMillis();
                    synchronized (deliverLock) {
                        if (pkt.isVideo()) {
                            cacheGop(pkt);
                        }
                        PacketEvent ev = new PacketEvent(gen, pkt);
                        for (Subscription s : subscriptions) {
                            s.offerPacket(ev);
                        }
                    }
                }
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                healthy = false;
                lastErrorAt = System.currentTimeMillis();
                // 设备重新上线、局域网 IP 变化、清晰度被修改时，不再继续等待熔断退避。
                boolean woke;
                synchronized (backoffLock) {
                    woke = wakeupRequested;
                    wakeupRequested = false;
                }
                long wait;
                if (woke) {
                    backoffIndex = 0;
                    consecutiveNoFrame = 0;
                    opener.resetFailures();
                    wait = 0;
                    log.info("[{}] 检测到设备状态变化，跳过熔断退避并立即重连（generation={}）", did, gen);
                } else {
                    wait = BACKOFF_MS[backoffIndex];
                    if (gotFrame) {
                        // 曾经出过帧，说明链路可用，按常规退避尽快恢复
                        consecutiveNoFrame = 0;
                        opener.noteFrame();
                    } else {
                        consecutiveNoFrame++;
                        opener.noteNoFrame();
                    }
                    // 连续「设备接受会话却一个媒体包都不给」时，立刻重连只会不停新建
                    // 上游会话。摄像头并发取流通道有限，必须逐步降频，等旧会话释放。
                    if (consecutiveNoFrame >= 12) {
                        wait = Math.max(wait, 300_000L);
                    } else if (consecutiveNoFrame >= 6) {
                        wait = Math.max(wait, 60_000L);
                    } else if (consecutiveNoFrame >= 3) {
                        wait = Math.max(wait, 10_000L);
                    }
                    log.warn("[{}] 上游取流中断（generation={}，连续无帧 {} 次）：{}；{}s 后自动重连（将重新协商）",
                            did, gen, consecutiveNoFrame, e.toString(), wait / 1000);
                }
                closeQuietly(client);
                client = null;
                current = null;
                if (wait > 0) {
                    synchronized (backoffLock) {
                        try {
                            if (!wakeupRequested) {
                                backoffLock.wait(wait);
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            if (!running) {
                                break;
                            }
                        }
                        // 无论是等待超时还是被 wakeup 唤醒，本次唤醒都已在重连中兑现
                        wakeupRequested = false;
                    }
                }
                if (backoffIndex < BACKOFF_MS.length - 1) {
                    backoffIndex++;
                }
            } finally {
                if (client != null) {
                    closeQuietly(client);
                    if (current == client) {
                        current = null;
                    }
                }
            }
        }
        log.info("[{}] 上游取流线程退出", did);
    }

    /**
     * 外部事件（设备上线、局域网可达性恢复、局域网 IP 变化、清晰度变更）触发的立即重连。
     *
     * <p>v1.1.8 引入的熔断退避最长 5 分钟，若设备在此期间恢复上线，预览窗口会一直黑屏到
     * 退避结束。这里打断等待、清零计数，并在需要时断开当前会话，让取流线程立刻重新协商。</p>
     *
     * @param reason          触发原因（写日志）
     * @param forceReconnect  true=连当前的会话也一并断开（设备刚上线 / IP 变了 / 档位改了时必须）
     */
    public void wakeup(String reason, boolean forceReconnect) {
        if (!running) {
            // 没有订阅者时无需唤醒：下次打开预览本来就是全新连接
            return;
        }
        log.info("[{}] 触发立即重连：{}（force={}）", did, reason, forceReconnect);
        synchronized (backoffLock) {
            wakeupRequested = true;
            backoffLock.notifyAll();
        }
        if (forceReconnect) {
            MissClient c = current;
            if (c != null) {
                // 断开会让 readPacket 抛错，取流线程随即走「立即重连」分支
                closeQuietly(c);
            }
        }
    }

    /**
     * 在现有上游会话上发送云台控制命令（不新建取流连接，不额外占用摄像头通道）。
     *
     * @return false 表示当前没有可用会话，调用方应提示先打开预览
     */
    public MotorResult motor(String direction, int speed, String mode) {
        MissClient c = current;
        if (c == null || !healthy) {
            return MotorResult.noSession(mode);
        }
        try {
            String reply = c.motor(direction, speed, mode);
            return new MotorResult(true, reply, mode);
        } catch (Exception e) {
            log.warn("[{}] 云台命令发送失败（{}）：{}", did, direction, e.toString());
            return MotorResult.noSession(mode);
        }
    }

    /** 云台命令/应答的异步快照；当前会话不存在时返回空快照。 */
    public MotorReply motorReply() {
        MissClient c = current;
        if (c == null) {
            return MotorReply.none();
        }
        return new MotorReply(c.motorReply(), c.motorReplyAt(), c.motorCommandAt());
    }

    /** 当前上游是否可用（有订阅者且已收到媒体包）。 */
    public boolean isHealthy() {
        return healthy && !subscriptions.isEmpty();
    }

    public long lastFrameAt() {
        return lastFrameAt;
    }

    public long lastErrorAt() {
        return lastErrorAt;
    }

    public int noFrameStreak() {
        return consecutiveNoFrame;
    }

    private void cacheGop(MissPacket p) {
        if (NalUnits.containsIdr(p.isH265(), p.payload())) {
            gop.clear();
            gopBytes = 0;
        }
        if (gopBytes < GOP_MAX_BYTES) {
            gop.add(p);
            gopBytes += p.payload().length;
        }
    }

    private static void closeQuietly(MissClient c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception e) {
            log.debug("关闭上游连接异常（忽略）：{}", e.toString());
        }
    }

    // ------------------------------------------------------------------
    // 订阅者：独立有界队列 + 独立派发线程，隔离慢消费者
    // ------------------------------------------------------------------

    /** 上游事件（代数重置 or 媒体包）。 */
    private sealed interface HubEvent permits ResetEvent, PacketEvent {
        int generation();
    }

    private record ResetEvent(int generation) implements HubEvent {
    }

    private record PacketEvent(int generation, MissPacket packet) implements HubEvent {
    }

    private static final Object POISON = new Object();

    private final class Subscription {
        final StreamSink sink;
        @SuppressWarnings("unchecked")
        final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(SUBSCRIBER_QUEUE_CAPACITY);
        Thread dispatcher;
        volatile boolean open = true;
        long dropped;

        Subscription(StreamSink sink) {
            this.sink = sink;
        }

        void start() {
            dispatcher = new Thread(this::runDispatch, "sink-dispatch-" + did + "-" + Integer.toHexString(hashCode()));
            dispatcher.setDaemon(true);
            dispatcher.start();
        }

        /** 新代数：旧积压包已无意义，清空后必达一个 reset。 */
        void dispatchReset(int gen) {
            queue.clear();
            queue.offer(new ResetEvent(gen));
        }

        void offerPacket(PacketEvent ev) {
            if (!queue.offer(ev)) {
                dropped++;
                if (dropped == 1 || dropped % 200 == 0) {
                    log.warn("[{}] 订阅者 {} 队列已满，丢弃 {} 包（消费者过慢）",
                            did, sink.getClass().getSimpleName(), dropped);
                }
            }
        }

        void close() {
            open = false;
            // v1.2.15：积压包在退订时已无意义，清空后直接投递 POISON，让分发线程立即收尾；
            // 否则它还要排空最多 1000 个包，调用方 purge() 会与在途原生写帧长时间重叠。
            queue.clear();
            queue.offer(POISON);
        }

        /** 等待分发线程退出（有界，不阻塞上游）；退订后不再有该 sink 的在途回调。 */
        void awaitStop() {
            Thread t = dispatcher;
            if (t == null || t == Thread.currentThread()) {
                return;
            }
            try {
                t.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (t.isAlive()) {
                log.warn("[{}] 订阅分发线程 {} 未能在 3s 内退出，已降级由 sink 自身互斥保护",
                        did, t.getName());
            }
        }

        private void runDispatch() {
            try {
                while (open || !queue.isEmpty()) {
                    Object e = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (e == null) {
                        continue;
                    }
                    if (e == POISON) {
                        break;
                    }
                    if (e instanceof ResetEvent r) {
                        sink.onReset(r.generation());
                    } else if (e instanceof PacketEvent p) {
                        sink.onPacket(p.packet(), p.generation());
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                log.error("[{}] 订阅者 {} 回调异常：{}", did, sink.getClass().getSimpleName(), t.toString(), t);
            }
        }
    }
}
