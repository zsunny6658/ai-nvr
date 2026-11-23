package com.ainvr.hub;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取流总线：按设备 DID 管理唯一的 {@link CameraStream}，是"单通道复用"的全局强制点。
 *
 * <p>无论同时有多少录像任务、多少 RTSP 播放器、多少 CLI 在看同一台摄像头，
 * {@code StreamHub} 对该 DID 只维护一个 {@link CameraStream}，即只占用一条上游 CS2/MISS 连接；
 * 所有消费者作为 {@link StreamSink} 被同一条上游扇出。</p>
 *
 * <p>同一 DID 的订阅/退订通过 {@link ConcurrentHashMap#compute} 在该 key 上原子串行，
 * 避免"最后一个退订者移除实例"与"新订阅者复用实例"竞争而误建第二条上游。</p>
 */
public final class StreamHub {

    private static final Logger log = LoggerFactory.getLogger(StreamHub.class);

    private final ConcurrentHashMap<String, CameraStream> streams = new ConcurrentHashMap<>();

    /**
     * 休眠闸门（v2.3.1）：did → 是否休眠中（true=不拨流）。装配期由
     * MiotSleepService 注入，之后创建的每条 CameraStream 都继承。
     */
    private volatile java.util.function.Predicate<String> sleepGate;

    /** 注入休眠闸门：立即作用于全部现存流，并供后续新建流继承。 */
    public void setSleepGate(java.util.function.Predicate<String> gate) {
        this.sleepGate = gate;
        for (CameraStream c : streams.values()) {
            c.setSleepGate(gate);
        }
    }

    /**
     * 订阅一台摄像头。
     *
     * @param did    设备 DID
     * @param model  型号（仅首次创建该设备流时使用）
     * @param opener 上游开启器（仅首次创建该设备流时使用；其内部每次重连都会重新协商）
     * @param sink   媒体消费者
     * @return 该设备对应的唯一 CameraStream
     */
    public CameraStream subscribe(String did, String model, MissOpener opener, StreamSink sink) {
        if (opener == null) {
            throw new IllegalArgumentException("opener 不能为空");
        }
        CameraStream cs = streams.compute(did, (d, existing) -> {
            CameraStream stream = existing != null ? existing : new CameraStream(d, model, opener);
            java.util.function.Predicate<String> gate = sleepGate;
            if (gate != null) {
                stream.setSleepGate(gate);
            }
            stream.subscribe(sink);
            return stream;
        });
        log.info("StreamHub 订阅 did={}（活跃上游设备数={}）", did, activeUpstreamCount());
        return cs;
    }

    /**
     * 退订一台摄像头；当该设备无任何消费者时，停止并移除其唯一上游。
     */
    public void unsubscribe(String did, StreamSink sink) {
        streams.compute(did, (d, stream) -> {
            if (stream == null) {
                return null;
            }
            stream.unsubscribe(sink);
            // 返回 null 即从 map 移除；compute 对同 key 原子，不会误删刚被复用的实例
            return stream.subscriberCount() == 0 ? null : stream;
        });
        log.info("StreamHub 退订 did={}（活跃上游设备数={}）", did, activeUpstreamCount());
    }

    /** 当前有订阅者、即真正占用上游通道的设备数。 */
    public int activeUpstreamCount() {
        int n = 0;
        for (CameraStream c : streams.values()) {
            if (c.subscriberCount() > 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 设备状态变化时打断该设备的重连退避。没有活跃订阅者时返回 false（下次打开预览即全新连接）。
     */
    public boolean wakeup(String did, String reason, boolean forceReconnect) {
        CameraStream cs = streams.get(did);
        if (cs == null) {
            return false;
        }
        cs.wakeup(reason, forceReconnect);
        return true;
    }

    /** 在已有上游会话上发送云台命令；无可用会话返回 noSession（绝不为此新建取流连接）。 */
    public MotorResult motor(String did, String direction, int speed, String mode) {
        CameraStream cs = streams.get(did);
        return cs == null ? MotorResult.noSession(mode) : cs.motor(direction, speed, mode);
    }

    /** 读取该设备最近一次云台命令与 0x113 应答的异步快照（前端延时轮询用）。 */
    public MotorReply motorReply(String did) {
        CameraStream cs = streams.get(did);
        return cs == null ? MotorReply.none() : cs.motorReply();
    }

    /** 在该设备的现有会话上开启对讲（0x106）；无健康会话返回 null。 */
    public CameraStream.SpeakerSession speakerStart(String did) {
        CameraStream cs = streams.get(did);
        return cs == null ? null : cs.speakerStart();
    }

    /** 上行一个对讲音频块；上游已重建/关闭时抛 IOException。 */
    public void speakerWrite(String did, CameraStream.SpeakerSession s, byte[] payload) throws java.io.IOException {
        CameraStream cs = streams.get(did);
        if (cs == null) {
            throw new java.io.IOException("设备当前没有取流会话");
        }
        cs.speakerWrite(s, payload);
    }

    /** 结束对讲（0x108，尽力而为）。 */
    public void speakerStop(String did, CameraStream.SpeakerSession s) {
        CameraStream cs = streams.get(did);
        if (cs != null) {
            cs.speakerStop(s);
        }
    }

    /** 该设备对讲开启/应答快照。 */
    public CameraStream.SpeakerReply speakerReply(String did) {
        CameraStream cs = streams.get(did);
        return cs == null ? CameraStream.SpeakerReply.none() : cs.speakerReply();
    }

    /** 该设备当前是否有一条健康的上游取流会话。 */
    public boolean isHealthy(String did) {
        CameraStream cs = streams.get(did);
        return cs != null && cs.isHealthy();
    }

    /** 当前有上游会话的设备 DID 列表（供状态接口/排障使用）。 */
    public java.util.List<String> activeDids() {
        return new java.util.ArrayList<>(streams.keySet());
    }

    /**
     * 单设备取流会话的只读诊断快照（设备自检 / 首页概览用）。
     *
     * <p>纯内存读取，<b>不会新建订阅、不会拉起上游、不发送任何设备命令</b>，因此可在
     * 5 秒级轮询中安全调用。{@code lastFrameAgeMs = -1} 表示从未收到过帧。</p>
     */
    public Map<String, Object> describe(String did) {
        CameraStream cs = streams.get(did);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", cs != null);
        m.put("subscribers", cs == null ? 0 : cs.subscriberCount());
        m.put("healthy", cs != null && cs.isHealthy());
        m.put("generation", cs == null ? 0 : cs.generation());
        m.put("lastFrameAt", cs == null ? 0L : cs.lastFrameAt());
        m.put("lastFrameAgeMs", cs == null || cs.lastFrameAt() <= 0
                ? -1L : Math.max(0L, System.currentTimeMillis() - cs.lastFrameAt()));
        m.put("lastErrorAt", cs == null ? 0L : cs.lastErrorAt());
        m.put("noFrameStreak", cs == null ? 0 : cs.noFrameStreak());
        return m;
    }

    /** 停止全部上游（服务关闭）。 */
    public void shutdown() {
        for (CameraStream c : streams.values()) {
            c.shutdown();
        }
        streams.clear();
    }
}
