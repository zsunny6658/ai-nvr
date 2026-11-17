package com.ainvr.web.service;

import com.ainvr.codec.SnapshotDecoder;
import com.ainvr.hub.CameraConnector;
import com.ainvr.hub.StreamHub;
import com.ainvr.hub.StreamSink;
import com.ainvr.miss.MissCommands;
import com.ainvr.miss.MissPacket;
import com.ainvr.model.CameraDevice;
import com.ainvr.rtsp.RtspServer;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.HostResolver;
import com.ainvr.store.MotionSettings;
import com.ainvr.web.api.ApiException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 设备自检：把「这台摄像头现在到底通不通」所需的散落状态聚合成一次只读查询。
 *
 * <p><b>默认浅检</b>：只读取内存状态（设备目录、取流会话、清晰度 / 云台 / 侦测设置、录像与
 * 预览标志），不订阅、不连设备、不产生任何上游流量，可在轮询里安全调用。</p>
 *
 * <p><b>{@code deep=true} 深检</b>：临时订阅一条 {@link StreamSink}（复用 StreamHub 的既有
 * 上游，不会为本设备建第二条连接），在最多 {@value #DEEP_TIMEOUT_MS} ms 内收集若干视频包，
 * 用项目自带的 {@link SnapshotDecoder} 解出首帧，从而得到编码、原始分辨率与按包时间戳估算
 * 的帧率；结束后立即退订。不需要外部 ffprobe 进程，也不会常驻占用摄像头通道。若该设备
 * 当前无人观看，深检会短暂拉起取流（这正是「固件升级后取流是否恢复」的排障手段）。</p>
 */
@Service
public class StreamProbeService {

    /** 深检整体上限（含冷启动建连与等 IDR）。 */
    public static final long DEEP_TIMEOUT_MS = 8_000;
    /** 解出首帧后继续观察的采样窗口，用于估算帧率。 */
    private static final long SAMPLE_WINDOW_MS = 1_500;
    /** 采样窗口内最多统计多少视频包，避免高码率设备长时间占用。 */
    private static final int MAX_VIDEO_PACKETS = 60;

    private final DeviceCatalog catalog;
    private final StreamHub hub;
    private final CameraConnector connector;
    private final RecordingService recordings;
    private final HlsService hls;
    private final AppSettingsStore settings;
    private final RtspServer rtspServer;
    private final HostResolver hostResolver = new HostResolver();

    public StreamProbeService(DeviceCatalog catalog, StreamHub hub, CameraConnector connector,
                              RecordingService recordings, HlsService hls,
                              AppSettingsStore settings, RtspServer rtspServer) {
        this.catalog = catalog;
        this.hub = hub;
        this.connector = connector;
        this.recordings = recordings;
        this.hls = hls;
        this.settings = settings;
        this.rtspServer = rtspServer;
    }

    public Map<String, Object> probe(String did, boolean deep) {
        CameraDevice device = catalog.findOrRefresh(did);
        if (device == null) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("did", device.did());
        m.put("name", device.name());
        m.put("model", device.model());
        String effectiveIp = hostResolver.resolve(device);
        m.put("localIp", effectiveIp != null ? effectiveIp : device.localIp());
        m.put("online", device.online());
        m.put("recording", guard(() -> recordings.isRecording(did), false));
        m.put("previewing", guard(() -> hls.isPreviewing(did), false));
        m.put("quality", guard(() -> settings.qualityFor(did), "auto"));
        m.put("ptzMode", guard(settings::ptzMode, "direction"));
        MotionSettings motion = guard(settings::motion, MotionSettings.DEFAULTS);
        m.put("motion", Map.of("enabled", motion.enabled(), "watched", motion.covers(did)));
        m.put("stream", guard(() -> hub.describe(did), Map.of("active", false)));
        m.put("rtspPort", guard(rtspServer::getPort, -1));
        m.put("checkedAt", System.currentTimeMillis());
        m.put("deep", false);

        if (deep) {
            m.put("deep", true);
            m.put("media", deepProbe(device));
        }
        return m;
    }

    /** 深检：临时订阅收集包统计 + 解一帧求分辨率，任何异常都转成可读结果而不是 500。 */
    private Map<String, Object> deepProbe(CameraDevice device) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!device.online()) {
            out.put("ok", false);
            out.put("error", "设备当前离线，跳过深度探测");
            return out;
        }
        long start = System.currentTimeMillis();
        SnapshotDecoder decoder;
        try {
            decoder = new SnapshotDecoder();
        } catch (Throwable t) {
            out.put("ok", false);
            out.put("error", "解码器初始化失败：" + t);
            return out;
        }

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger videoPackets = new AtomicInteger();
        AtomicInteger audioPackets = new AtomicInteger();
        AtomicInteger codecId = new AtomicInteger(0);
        AtomicLong firstTs = new AtomicLong();
        AtomicLong lastTs = new AtomicLong();
        AtomicLong firstFrameMs = new AtomicLong(-1);
        AtomicReference<byte[]> jpeg = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>("");

        StreamSink sink = new StreamSink() {
            @Override
            public void onReset(int generation) {
                try {
                    decoder.reset();
                } catch (Throwable t) {
                    error.compareAndSet("", "解码器重置失败：" + t);
                }
            }

            @Override
            public void onPacket(MissPacket pkt, int generation) {
                if (pkt == null || pkt.payload() == null) {
                    return;
                }
                if (!pkt.isVideo()) {
                    audioPackets.incrementAndGet();
                    return;
                }
                int n = videoPackets.incrementAndGet();
                if (n == 1) {
                    codecId.set(pkt.codecId());
                    firstTs.set(pkt.timestampMs());
                }
                lastTs.set(pkt.timestampMs());
                try {
                    byte[] j = decoder.accept(pkt);
                    if (j != null && jpeg.compareAndSet(null, j)) {
                        firstFrameMs.set(System.currentTimeMillis() - start);
                    }
                } catch (Throwable t) {
                    error.compareAndSet("", "解码失败：" + t);
                    done.countDown();
                    return;
                }
                boolean enoughPackets = n >= MAX_VIDEO_PACKETS;
                boolean enoughTime = jpeg.get() != null
                        && System.currentTimeMillis() - start >= SAMPLE_WINDOW_MS;
                if (enoughPackets || enoughTime) {
                    done.countDown();
                }
            }
        };

        boolean subscribed = false;
        try {
            hub.subscribe(device.did(), device.model(), connector.openerFor(device), sink);
            subscribed = true;
            done.await(DEEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            error.compareAndSet("", "取流失败：" + t);
        } finally {
            if (subscribed) {
                try {
                    hub.unsubscribe(device.did(), sink);
                } catch (Throwable ignored) {
                    // 退订失败不应影响已经拿到的探测结果
                }
            }
            try {
                decoder.close();
            } catch (Throwable ignored) {
                // native 资源释放失败仅记录，不覆盖探测结果
            }
        }

        int packets = videoPackets.get();
        out.put("ok", packets > 0 && jpeg.get() != null && error.get().isEmpty());
        out.put("codec", codecName(codecId.get()));
        out.put("videoPackets", packets);
        out.put("audioPackets", audioPackets.get());
        out.put("firstFrameMs", firstFrameMs.get());
        out.put("sampleMs", System.currentTimeMillis() - start);
        long span = lastTs.get() - firstTs.get();
        double fps = packets > 1 && span > 0 ? (packets - 1) * 1000.0 / span : 0.0;
        out.put("fpsEstimate", Math.round(fps * 10) / 10.0);
        out.put("fpsNote", "按媒体包时间戳估算，仅供参考；多 NALU 合帧时会偏高");

        byte[] j = jpeg.get();
        out.put("snapshotBytes", j == null ? 0 : j.length);
        if (j != null) {
            try {
                var img = ImageIO.read(new ByteArrayInputStream(j));
                if (img != null) {
                    out.put("width", img.getWidth());
                    out.put("height", img.getHeight());
                }
            } catch (Throwable ignored) {
                // JPEG 尺寸读取失败不影响其余探测字段
            }
        }
        if (!error.get().isEmpty()) {
            out.put("error", error.get());
        }
        if (packets == 0) {
            out.put("hint", "未收到任何视频包：请检查摄像头在线状态、固件取流服务与局域网连通性");
        }
        return out;
    }

    private static String codecName(int codecId) {
        if (codecId == MissCommands.CODEC_H265) {
            return "H.265 / HEVC";
        }
        if (codecId == MissCommands.CODEC_H264) {
            return "H.264 / AVC";
        }
        return codecId == 0 ? "未知（未收到视频包）" : "未知 codecId=" + codecId;
    }

    private static <T> T guard(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            T v = supplier.get();
            return v == null ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
