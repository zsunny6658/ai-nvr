package com.ainvr.record;

import com.ainvr.web.service.RecordingService;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avformat.AVIOContext;
import org.bytedeco.ffmpeg.avformat.AVStream;
import org.bytedeco.ffmpeg.avutil.AVDictionary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

import static org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_unref;
import static org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame;
import static org.bytedeco.ffmpeg.global.avformat.av_write_trailer;
import static org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy;
import static org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE;
import static org.bytedeco.ffmpeg.global.avformat.av_find_best_stream;
import static org.bytedeco.ffmpeg.global.avformat.av_read_frame;
import static org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2;
import static org.bytedeco.ffmpeg.global.avformat.avformat_close_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info;
import static org.bytedeco.ffmpeg.global.avformat.avformat_free_context;
import static org.bytedeco.ffmpeg.global.avformat.avformat_new_stream;
import static org.bytedeco.ffmpeg.global.avformat.avformat_open_input;
import static org.bytedeco.ffmpeg.global.avformat.avformat_write_header;
import static org.bytedeco.ffmpeg.global.avformat.avio_closep;
import static org.bytedeco.ffmpeg.global.avformat.avio_open;
import static org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_free;
import static org.bytedeco.ffmpeg.global.avutil.av_dict_set;

/**
 * v2.3.1 第二十三轮：iOS 回放兼容层。
 *
 * <p>背景：v2.3.1 之前录制的分段视频轨 sample entry 是 <b>hev1</b>（ffmpeg mp4 封装器
 * 对裸 HEVC 流的默认标签）——桌面 Chrome/Edge 可播，但 iPhone 媒体栈只认 <b>hvc1</b>，
 * 遇到 hev1 直接 {@code MEDIA_ERR_SRC_NOT_SUPPORTED}（error code=4），手机回放黑屏。</p>
 *
 * <p>策略：<b>按需转封装</b>（lazy remux），不做全量后台扫描（1.4 万+ 段全量重写代价高且
 * 绝大多数段用户不会回看）。首播某段时探测其 moov 里的 sample entry：hev1 → 视频流
 * 0 拷贝转封装为 hvc1（音轨原样复制），缓存于录像目录下 {@code .ainvr-ios/} 子目录，
 * 命中缓存直接返回。转封装不重编码，2 分钟段通常 &lt;1s。</p>
 */
@Service
public class IosRemuxService {

    private static final Logger log = LoggerFactory.getLogger(IosRemuxService.class);

    /** 探测窗口：fMP4 的 moov 由 empty_moov 写在文件头，stsd 标签必在前 64KB 内。 */
    private static final int PROBE_BYTES = 64 * 1024;

    private final RecordingService recordings;
    private final Object lock = new Object(); // 转封装串行化（同段并发请求共享一次输出）

    public IosRemuxService(RecordingService recordings) {
        this.recordings = recordings;
    }

    /** 返回 iPhone 可播放的文件：原文件已是 hvc1/avc1 → 原样返回；hev1 → 转封装缓存。 */
    public File playable(File src, boolean force) {
        if (src == null || !src.isFile()) {
            return src;
        }
        if (!force && !needsRemux(src)) {
            return src;
        }
        File cache = cacheFile(src);
        if (cache.isFile() && cache.lastModified() >= src.lastModified()) {
            return cache;
        }
        synchronized (lock) {
            // 双检：等锁期间别的请求可能已完成转封装
            if (cache.isFile() && cache.lastModified() >= src.lastModified()) {
                return cache;
            }
            remuxToHvc1(src, cache);
            return cache.isFile() ? cache : src; // 失败兜底：退回原文件（桌面仍可播）
        }
    }

    /** 删除录像段时同步清理缓存（供 RecordingService.delete 调用）。 */
    public void evictCache(String name) {
        File c = cacheFile0(name);
        if (c.isFile()) {
            try {
                java.nio.file.Files.deleteIfExists(c.toPath());
            } catch (IOException e) {
                log.warn("清理 iOS 转封装缓存失败：{} - {}", c, e.getMessage());
            }
        }
    }

    private File cacheDir() {
        return new File(recordings.rootDir(), ".ainvr-ios");
    }

    private File cacheFile(File src) {
        return new File(cacheDir(), src.getName());
    }

    private File cacheFile0(String name) {
        return new File(cacheDir(), name);
    }

    /** moov 头部含 hev1 → 需要 hvc1 转封装；hvc1/avc1 或无 moov（未写完头）→ 原样。 */
    boolean needsRemux(File src) {
        try (RandomAccessFile raf = new RandomAccessFile(src, "r")) {
            byte[] buf = new byte[PROBE_BYTES];
            int n = raf.read(buf);
            if (n <= 0) {
                return false;
            }
            String head = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
            // hev1 必现于 stsd；hvc1 视为已兼容。两标签同框（理论不可能）以 hvc1 优先。
            if (head.contains("hvc1") || head.contains("avc1")) {
                return false;
            }
            return head.contains("hev1");
        } catch (IOException e) {
            log.warn("探测 {} 失败，按不需转封装处理：{}", src.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * 视频流 0 拷贝重贴 hvc1 标签、其余流原样复制的 fMP4 转封装。
     * 保持与录制侧一致的分片参数（frag_keyframe+empty_moov+default_base_moof），
     * 正在录制的段也能转出可读的「已写部分」。
     */
    private void remuxToHvc1(File src, File dst) {
        AVFormatContext in = null;
        AVFormatContext out = null;
        AVIOContext outPb = null;
        try {
            in = new AVFormatContext(null);
            int rc = avformat_open_input(in, src.getAbsolutePath(), null, null);
            if (rc < 0) {
                throw new IOException("open_input 失败 rc=" + rc);
            }
            avformat_find_stream_info(in, (org.bytedeco.javacpp.PointerPointer) null);

            out = new AVFormatContext(null);
            rc = avformat_alloc_output_context2(out, null, "mp4", dst.getAbsolutePath());
            if (rc < 0) {
                throw new IOException("alloc output 失败 rc=" + rc);
            }
            int videoIdx = av_find_best_stream(in, org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO,
                    -1, -1, (org.bytedeco.javacpp.PointerPointer) null, 0);

            for (int i = 0; i < in.nb_streams(); i++) {
                AVStream is = in.streams(i);
                AVStream os = avformat_new_stream(out, (org.bytedeco.ffmpeg.avcodec.AVCodec) null);
                if (os == null) {
                    throw new IOException("new_stream 失败 stream=" + i);
                }
                org.bytedeco.ffmpeg.avcodec.AVCodecParameters p = os.codecpar();
                avcodec_parameters_copy(p, is.codecpar());
                os.time_base(is.time_base());
                if (i == videoIdx && p.codec_id() == org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC) {
                    p.codec_tag(Mp4SegmentRecorder.mktag('h', 'v', 'c', '1'));
                }
            }

            outPb = new AVIOContext(null);
            if (avio_open(outPb, dst.getAbsolutePath(), AVIO_FLAG_WRITE) < 0) {
                throw new IOException("avio_open 失败：" + dst);
            }
            out.pb(outPb);
            AVDictionary opt = new AVDictionary();
            av_dict_set(opt, "movflags", "frag_keyframe+empty_moov+default_base_moof", 0);
            rc = avformat_write_header(out, opt);
            av_dict_free(opt);
            if (rc < 0) {
                throw new IOException("write_header 失败 rc=" + rc);
            }

            AVPacket pkt = av_packet_alloc();
            try {
                while (true) {
                    rc = av_read_frame(in, pkt);
                    if (rc == AVERROR_EOF || rc < 0) {
                        break;
                    }
                    // 源本身就是 fMP4 录制段，包序已规整；直接写，不做 interleaving 重排
                    av_packet_rescale_ts(pkt,
                            in.streams(pkt.stream_index()).time_base(),
                            out.streams(pkt.stream_index()).time_base());
                    rc = av_interleaved_write_frame(out, pkt);
                    av_packet_unref(pkt);
                    if (rc < 0) {
                        throw new IOException("写包失败 rc=" + rc);
                    }
                }
                av_write_trailer(out);
            } finally {
                av_packet_free(pkt);
            }
            log.info("iOS 转封装完成：{} → {}（{}B）", src.getName(), dst.getName(), dst.length());
        } catch (Exception e) {
            log.warn("iOS 转封装失败 {}：{}", src.getName(), e.getMessage());
            try {
                java.nio.file.Files.deleteIfExists(dst.toPath());
            } catch (IOException ignore) { }
        } finally {
            if (out != null && outPb != null && out.pb() != null) {
                try { avio_closep(outPb); } catch (Exception ignore) { }
            }
            if (in != null) {
                try { avformat_close_input(in); } catch (Exception ignore) { }
            }
            if (out != null) {
                try { avformat_free_context(out); } catch (Exception ignore) { }
            }
        }
    }
}
