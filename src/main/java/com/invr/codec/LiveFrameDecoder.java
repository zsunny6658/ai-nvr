package com.invr.codec;

import com.invr.miss.MissPacket;
import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MJPEG;
import static org.bytedeco.ffmpeg.global.avcodec.av_new_packet;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUVJ420P;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;
import static org.bytedeco.ffmpeg.global.avutil.av_strerror;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;
import static org.bytedeco.ffmpeg.global.swscale.sws_freeContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_getContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale_frame;

/**
 * 持续截帧解码器：把 H.265/H.264 Annex-B 媒体包解码并按固定帧率上限输出 JPEG。
 *
 * <p>与 {@link SnapshotDecoder}（一次性取首帧）不同，本类保持解码器常驻、由
 * {@link com.invr.web.service.LiveFrameService} 长期订阅同一条上游，供「兼容画面模式」使用：
 * 浏览器不支持 H.265 时，前端改为轮询 JPEG 帧，从而绕开 MSE/原生 HLS 的 HEVC 解码限制。
 * 所有媒体包都照常送入解码器（维持参考帧链），节流只作用于 MJPEG 编码，避免花屏。</p>
 *
 * <p>输出按 {@code maxWidth} 等比缩小（默认 640），降低移动端流量与编码开销。全部 FFmpeg
 * 调用发生在订阅派发线程；{@link #close()} 可能在回收线程调用，故方法均 synchronized。</p>
 */
public final class LiveFrameDecoder {

    private static final Logger log = LoggerFactory.getLogger(LiveFrameDecoder.class);

    static {
        av_log_set_level(AV_LOG_WARNING);
    }

    private final long minIntervalMs;
    private final int maxWidth;

    private AVCodecContext decoder;
    private AVPacket packet;
    private AVFrame decFrame;
    private boolean opened;
    private boolean closed;
    private long lastJpegAt;

    public LiveFrameDecoder(long minIntervalMs, int maxWidth) {
        this.minIntervalMs = Math.max(50L, minIntervalMs);
        this.maxWidth = maxWidth;
    }

    /** 上游重连（新 generation）：清空解码器缓存，随后到达的新 IDR 可独立解码。 */
    public synchronized void reset() {
        if (opened && decoder != null) {
            avcodec_flush_buffers(decoder);
        }
    }

    /**
     * 喂入一个媒体包；本帧被节流或尚未解出图像时返回 null，否则返回一张完整 JPEG。
     */
    public synchronized byte[] accept(MissPacket pkt) {
        if (closed || pkt == null || !pkt.isVideo()
                || pkt.payload() == null || pkt.payload().length == 0) {
            return null;
        }
        ensureOpen(pkt.isH265());

        byte[] payload = pkt.payload();
        int fillRc = av_new_packet(packet, payload.length);
        if (fillRc < 0) {
            throw new IllegalStateException("兼容画面解码分配包失败：" + ffmpegError(fillRc));
        }
        try {
            packet.data().put(payload, 0, payload.length);
            int sendRc = avcodec_send_packet(decoder, packet);
            if (sendRc < 0) {
                log.debug("兼容画面解码送包返回 {}，跳过该包", ffmpegError(sendRc));
                return null;
            }
        } finally {
            av_packet_unref(packet);
        }

        byte[] jpeg = null;
        int rc;
        // 一次送包可能输出 0..N 帧：每帧都要 unref，只在节流窗口内编码其中一帧
        while ((rc = avcodec_receive_frame(decoder, decFrame)) >= 0) {
            try {
                if (jpeg == null && System.currentTimeMillis() - lastJpegAt >= minIntervalMs) {
                    jpeg = encodeJpeg(decFrame);
                    lastJpegAt = System.currentTimeMillis();
                }
            } finally {
                av_frame_unref(decFrame);
            }
        }
        if (rc < 0 && rc != -11 /* EAGAIN */ && rc != -541478725 /* EOF */) {
            log.debug("兼容画面解码收帧返回 {}", ffmpegError(rc));
        }
        return jpeg;
    }

    private void ensureOpen(boolean h265) {
        if (opened) {
            return;
        }
        int codecId = h265 ? AV_CODEC_ID_HEVC : AV_CODEC_ID_H264;
        AVCodec codec = avcodec_find_decoder(codecId);
        if (codec == null) {
            throw new IllegalStateException("未找到 " + (h265 ? "H.265" : "H.264") + " 解码器");
        }
        decoder = avcodec_alloc_context3(codec);
        if (decoder == null) {
            throw new IllegalStateException("分配视频解码器上下文失败");
        }
        int rc = avcodec_open2(decoder, codec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
        if (rc < 0) {
            avcodec_free_context(decoder);
            decoder = null;
            throw new IllegalStateException("打开视频解码器失败：" + ffmpegError(rc));
        }
        packet = av_packet_alloc();
        decFrame = av_frame_alloc();
        if (packet == null || decFrame == null) {
            throw new IllegalStateException("分配解码 packet/frame 失败");
        }
        opened = true;
        log.info("兼容画面解码器已打开（{}，≤{}fps，宽≤{}）", h265 ? "H.265" : "H.264",
                Math.round(1000.0 / minIntervalMs), maxWidth);
    }

    /** 解码帧 → 等比缩小 → YUVJ420P → MJPEG 编码。编码器/缩放器为每次编码临时资源。 */
    private byte[] encodeJpeg(AVFrame src) {
        int width = src.width();
        int height = src.height();
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("解码帧分辨率无效：" + width + "x" + height);
        }
        int outW = width;
        int outH = height;
        if (maxWidth > 0 && width > maxWidth) {
            outW = maxWidth;
            outH = Math.max(2, Math.round(height * (float) maxWidth / width));
            outH -= outH % 2;
        }

        AVCodec jpegCodec = avcodec_find_encoder(AV_CODEC_ID_MJPEG);
        if (jpegCodec == null) {
            throw new IllegalStateException("未找到 MJPEG 编码器");
        }
        AVCodecContext enc = avcodec_alloc_context3(jpegCodec);
        AVFrame dst = av_frame_alloc();
        AVPacket encPkt = av_packet_alloc();
        SwsContext sws = null;
        try {
            enc.width(outW);
            enc.height(outH);
            enc.pix_fmt(AV_PIX_FMT_YUVJ420P);
            enc.time_base(new AVRational().num(1).den(25));
            int rc = avcodec_open2(enc, jpegCodec, (org.bytedeco.ffmpeg.avutil.AVDictionary) null);
            if (rc < 0) {
                throw new IllegalStateException("打开 MJPEG 编码器失败：" + ffmpegError(rc));
            }

            dst.width(outW);
            dst.height(outH);
            dst.format(AV_PIX_FMT_YUVJ420P);
            rc = av_frame_get_buffer(dst, 32);
            if (rc < 0) {
                throw new IllegalStateException("分配 JPEG 帧缓冲失败：" + ffmpegError(rc));
            }

            sws = sws_getContext(width, height, src.format(),
                    outW, outH, AV_PIX_FMT_YUVJ420P,
                    SWS_BILINEAR, null, null, (double[]) null);
            if (sws == null) {
                throw new IllegalStateException("创建图像缩放上下文失败");
            }
            rc = sws_scale_frame(sws, dst, src);
            if (rc < 0) {
                throw new IllegalStateException("像素格式转换失败：" + ffmpegError(rc));
            }
            dst.pts(0);

            rc = avcodec_send_frame(enc, dst);
            if (rc < 0) {
                throw new IllegalStateException("MJPEG 送帧失败：" + ffmpegError(rc));
            }
            rc = avcodec_receive_packet(enc, encPkt);
            if (rc < 0) {
                throw new IllegalStateException("MJPEG 收包失败：" + ffmpegError(rc));
            }
            byte[] jpeg = new byte[encPkt.size()];
            encPkt.data().get(jpeg);
            return jpeg;
        } finally {
            if (sws != null) {
                sws_freeContext(sws);
            }
            av_packet_unref(encPkt);
            av_packet_free(encPkt);
            av_frame_free(dst);
            avcodec_free_context(enc);
        }
    }

    /** 释放全部 native 资源；可重复调用。 */
    public synchronized void close() {
        closed = true;
        if (packet != null) {
            av_packet_free(packet);
            packet = null;
        }
        if (decFrame != null) {
            av_frame_free(decFrame);
            decFrame = null;
        }
        if (decoder != null) {
            avcodec_free_context(decoder);
            decoder = null;
        }
        opened = false;
    }

    private static String ffmpegError(int rc) {
        org.bytedeco.javacpp.BytePointer buf = new org.bytedeco.javacpp.BytePointer(256);
        av_strerror(rc, buf, 256);
        String s = buf.getString();
        buf.close();
        return s + "(rc=" + rc + ")";
    }
}
