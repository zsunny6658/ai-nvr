package com.ainvr.record;

import com.ainvr.hub.StreamSink;
import com.ainvr.miss.MissCommands;
import com.ainvr.miss.MissPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * 录像消费者：订阅 StreamHub，把视频/音频写入 {@link Mp4SegmentRecorder} 的分段 MP4。
 *
 * <p>与 RTSP 等其他消费者共享同一条上游 CS2（单通道复用）。首个视频包到达时按实际编码
 * （H.265/H.264）懒初始化录像器；上游重连仅累计计数，时间轴连续性由录像器归一化处理。</p>
 */
public final class RecordingSink implements StreamSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RecordingSink.class);
    /** c302 等 2020 后机型 Opus 为 48kHz 双声道。 */
    private static final int OPUS_CHANNELS = 2;

    private final String did;
    private final File outDir;
    private final long segmentSeconds;

    private Mp4SegmentRecorder recorder;
    /** 分段生命周期回调（透传给 {@link Mp4SegmentRecorder}，可为 null）。 */
    private final Mp4SegmentRecorder.SegmentListener segmentListener;
    private int currentGen;
    private int reconnects;
    private long videoPackets;
    private long videoBytes;
    private long audioPackets;

    public RecordingSink(String did, File outDir, long segmentSeconds) {
        this(did, outDir, segmentSeconds, null);
    }

    /**
     * @param listener 分段开段/封段回调（可选）。由 {@code RecordingService} 传入以维护
     *                 录像分段内存索引；事件驱动比目录 mtime 轮询可靠（粗粒度文件系统上
     *                 mtime 可能延迟更新）。
     */
    public RecordingSink(String did, File outDir, long segmentSeconds,
                         Mp4SegmentRecorder.SegmentListener listener) {
        this.did = did;
        this.outDir = outDir;
        this.segmentSeconds = segmentSeconds;
        this.segmentListener = listener;
    }

    @Override
    public void onReset(int generation) {
        currentGen = generation;
        if (generation > 1) {
            reconnects++;
            log.info("[{}] 录像感知上游重连（generation={}，累计 {} 次），分段时间轴自动续接",
                    did, generation, reconnects);
        }
    }

    @Override
    public void onPacket(MissPacket pkt, int generation) {
        if (generation != currentGen) {
            return;
        }
        try {
            if (pkt.isVideo()) {
                if (recorder == null) {
                    recorder = new Mp4SegmentRecorder(did, outDir, segmentSeconds,
                            pkt.isH265(), OPUS_CHANNELS, segmentListener);
                    recorder.start();
                    log.info("[{}] 开始 MP4 分段录像（{}，{}s/段，目录 {}）",
                            did, pkt.isH265() ? "H.265" : "H.264", segmentSeconds,
                            outDir.getAbsolutePath());
                }
                recorder.feedVideo(pkt.payload(), pkt.timestampMs());
                videoPackets++;
                videoBytes += pkt.payload().length;
            } else if (pkt.codecId() == MissCommands.CODEC_OPUS && recorder != null) {
                recorder.feedAudio(pkt.payload(), pkt.timestampMs());
                audioPackets++;
            }
        } catch (IOException e) {
            throw new RuntimeException("录像写入失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (recorder != null) {
            recorder.stop();
        }
    }

    public int reconnects() {
        return reconnects;
    }

    public long videoPackets() {
        return videoPackets;
    }

    public long videoBytes() {
        return videoBytes;
    }

    public long audioPackets() {
        return audioPackets;
    }

    public long segmentCount() {
        return recorder == null ? 0 : recorder.segmentCount();
    }

    /** 当前正在写入的分段文件名；未开始/已封段/已停止时为 null。 */
    public String currentSegmentName() {
        return recorder == null ? null : recorder.currentSegmentName();
    }
}
