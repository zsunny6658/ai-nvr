package com.ainvr.record;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVChannelLayout;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_open2;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO;
import static org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO;
import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;
import static org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_alloc;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_free;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer;
import static org.bytedeco.ffmpeg.global.avutil.av_frame_unref;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mp4SegmentRecorder AAC 音轨端到端验证：测试内用 libopus 编码器生成真实 Opus 包喂入
 * 录像器，录像输出后重新 demux + 解码，断言音轨已是 AAC 且解码样本时长与输入一致。
 * 视频 NAL 用合成字节（录像器只做封装，不解码校验流内容）。
 */
class Mp4SegmentRecorderAacTest {

    private static final int RATE = 48_000;
    private static final int CH = 1;
    /** 喂 5 秒音频。 */
    private static final int FEED_MS = 5_000;

    @TempDir
    Path tempDir;

    // ---------------- 输入侧：合成视频 NAL + 真实 Opus ----------------

    /** 手工构造的合法 baseline SPS（640×480，无裁剪）：录像器会解析它得出 MP4 trak 尺寸。 */
    private static final byte[] SPS = {0x67, 0x42, 0x00, 0x1E, (byte) 0xF4, 0x05, 0x01, (byte) 0xEC, (byte) 0x80};
    private static final byte[] PPS = {0x68, (byte) 0xeb, (byte) 0xec, (byte) 0xb2, 0x2c};

    /** 最小 H.264 访问单元：SPS + PPS + IDR。 */
    private static byte[] idrAu() {
        byte[] idr = {0x65, (byte) 0x88, (byte) 0x84, 0x00, 0x10, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00};
        return au(SPS, PPS, idr);
    }

    private static byte[] pAu() {
        byte[] p = {0x41, (byte) 0x9a, 0x02, 0x05, 0x21};
        return au(SPS, PPS, p);
    }

    private static byte[] au(byte[]... nals) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        for (byte[] nal : nals) {
            o.write(0);
            o.write(0);
            o.write(0);
            o.write(1);
            o.write(nal, 0, nal.length);
        }
        return o.toByteArray();
    }

    /** 用 libopus 编码器把正弦 PCM 压成真实 Opus 包序列（20ms/包，与编码器默认帧长一致）。 */
    private static byte[][] encodeOpusPackets() throws Throwable {
        av_log_set_level(AV_LOG_ERROR);
        AVCodec codec = avcodec_find_encoder(AV_CODEC_ID_OPUS);
        assertNotNull(codec, "libopus 编码器应可用");
        AVCodecContext enc = avcodec_alloc_context3(codec);
        AVChannelLayout layout = new AVChannelLayout();
        av_channel_layout_default(layout, CH);
        enc.ch_layout(layout);
        enc.sample_rate(RATE);
        enc.sample_fmt(AV_SAMPLE_FMT_S16);
        enc.bit_rate(48_000);
        assertTrue(avcodec_open2(enc, codec, (AVDictionary) null) >= 0, "opus 编码器应能打开");

        int frameSamples = enc.frame_size(); // libopus 默认 20ms = 960 样本
        int totalSamples = RATE * FEED_MS / 1000;
        byte[][] out = new byte[(totalSamples + frameSamples - 1) / frameSamples][];
        AVFrame frame = av_frame_alloc();
        frame.format(AV_SAMPLE_FMT_S16);
        frame.ch_layout(layout);
        frame.sample_rate(RATE);
        frame.nb_samples(frameSamples);
        assertTrue(av_frame_get_buffer(frame, 0) >= 0);
        AVPacket pkt = av_packet_alloc();

        int produced = 0;
        int phase = 0;
        while (produced < totalSamples) {
            for (int i = 0; i < frameSamples; i++) {
                short s = (short) Math.round(Math.sin(2 * Math.PI * 440 * (phase + i) / RATE) * 9800);
                frame.data(0).getPointer(i * 2L).putShort(s);
            }
            phase += frameSamples;
            frame.pts(produced);
            assertEquals(0, avcodec_send_frame(enc, frame));
            while (avcodec_receive_packet(enc, pkt) == 0) {
                byte[] b = new byte[(int) pkt.size()];
                pkt.data().get(b, 0, b.length);
                int idx = produced / frameSamples;
                if (idx < out.length) {
                    out[idx] = b;
                }
                av_packet_unref(pkt);
            }
            produced += frameSamples;
        }
        avcodec_send_frame(enc, null);
        while (avcodec_receive_packet(enc, pkt) == 0) {
            av_packet_unref(pkt); // 尾部残余（不足一帧）忽略
        }
        av_frame_free(frame);
        av_packet_free(pkt);
        avcodec_free_context(enc);
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) {
                out[i] = new byte[] {0x00}; // 极小哑包补位，保证节奏
            }
        }
        return out;
    }

    @Test
    void parseAvcDimensionsFromCraftedSps() {
        byte[] au = au(SPS, PPS, new byte[] {0x65, 0x11});
        int[] dim = Mp4SegmentRecorder.parseAvcDimensionsFromAu(au);
        assertNotNull(dim, "构造的 SPS 应能解析出尺寸");
        assertEquals(640, dim[0]);
        assertEquals(480, dim[1]);
    }

    @Test
    void audioTrackIsAacAndDecodable() throws Throwable {
        byte[][] opusPackets = encodeOpusPackets();
        assertTrue(opusPackets.length >= 100, "应有约 250 个 20ms Opus 包：实际 " + opusPackets.length);

        File dir = tempDir.toFile();
        Mp4SegmentRecorder rec = new Mp4SegmentRecorder("cam1", dir, 60, false, CH);
        rec.start();
        // 视频 25fps×5s（40ms 步进）；音频 20ms/包同步推进，设备毫秒时间戳同源
        byte[] idr = idrAu();
        int audioIdx = 0;
        for (int t = 0; t < FEED_MS; t += 40) {
            rec.feedVideo(t == 0 ? idr : pAu(), t);
            for (int a = 0; a < 2 && audioIdx < opusPackets.length; a++, audioIdx++) {
                rec.feedAudio(opusPackets[audioIdx], t + a * 20);
            }
        }
        rec.stop();
        assertTrue(rec.segmentCount() >= 1, "至少产出 1 个分段");

        File mp4 = null;
        for (File f : dir.listFiles()) {
            if (f.getName().endsWith(".mp4")) {
                mp4 = f;
            }
        }
        assertNotNull(mp4, "应有 MP4 分段文件");
        verifyAac(mp4);
    }

    /** 读回输出：音轨必须是 AAC，解码总样本数覆盖 ≥90% 时长，pts 单调不减。 */
    private static void verifyAac(File mp4) throws Throwable {
        AVFormatContext in = new AVFormatContext(null);
        assertEquals(0, avformat_open_input(in, mp4.getAbsolutePath(), null, (AVDictionary) null),
                "输出应能正常打开：" + mp4.getName());
        assertEquals(0, avformat_find_stream_info(in, (AVDictionary) null));
        int audioIdx = -1;
        boolean hasVideo = false;
        for (int i = 0; i < in.nb_streams(); i++) {
            int type = in.streams(i).codecpar().codec_type();
            if (type == AVMEDIA_TYPE_AUDIO) {
                audioIdx = i;
            } else if (type == AVMEDIA_TYPE_VIDEO) {
                hasVideo = true;
            }
        }
        assertTrue(hasVideo, "应有视频轨");
        assertTrue(audioIdx >= 0, "应有音频轨");
        assertEquals(AV_CODEC_ID_AAC, in.streams(audioIdx).codecpar().codec_id(),
                "音轨应为 AAC（浏览器可播），实际 codec_id=" + in.streams(audioIdx).codecpar().codec_id());
        assertEquals(RATE, in.streams(audioIdx).codecpar().sample_rate());

        AVCodec dec = avcodec_find_decoder(AV_CODEC_ID_AAC);
        assertNotNull(dec);
        AVCodecContext decCtx = avcodec_alloc_context3(dec);
        assertTrue(avcodec_parameters_to_context(decCtx, in.streams(audioIdx).codecpar()) >= 0,
                "流参数（含 ASC extradata）应拷入解码器");
        assertTrue(avcodec_open2(decCtx, dec, (AVDictionary) null) >= 0);
        AVFrame frame = av_frame_alloc();
        AVPacket pkt = av_packet_alloc();
        long samples = 0;
        long lastPts = -1;
        boolean ptsMonotonic = true;
        int badPkts = 0;
        while (av_read_frame(in, pkt) == 0) {
            if (pkt.stream_index() == audioIdx) {
                if (avcodec_send_packet(decCtx, pkt) < 0) {
                    badPkts++; // 个别坏包容错：跳过继续
                    av_packet_unref(pkt);
                    continue;
                }
                while (avcodec_receive_frame(decCtx, frame) == 0) {
                    samples += frame.nb_samples();
                    if (lastPts >= 0 && frame.pts() >= 0 && frame.pts() < lastPts) {
                        ptsMonotonic = false;
                    }
                    lastPts = frame.pts();
                }
                av_frame_unref(frame);
            }
            av_packet_unref(pkt);
        }
        assertTrue(badPkts <= 2, "AAC 坏包不应超过 2 个，实际 " + badPkts);
        avcodec_send_packet(decCtx, null);
        while (avcodec_receive_frame(decCtx, frame) == 0) {
            samples += frame.nb_samples();
        }
        long need = RATE * FEED_MS / 1000 * 90 / 100;
        assertTrue(samples >= need,
                "解码音频应覆盖 ≥90% 时长（≥" + need + " 样本），实际 " + samples);
        assertTrue(ptsMonotonic, "AAC pts 应单调不减");
        av_frame_free(frame);
        av_packet_free(pkt);
        avcodec_free_context(decCtx);
        avformat_close_input(in);
        avformat_free_context(in);
    }
}
