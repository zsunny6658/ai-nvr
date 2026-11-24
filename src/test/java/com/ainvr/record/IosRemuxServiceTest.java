package com.ainvr.record;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.3.1 第二十三轮：iOS 回放兼容层（hev1 → hvc1）。
 *
 * <p>真机实锤：录制段 moov/stsd 的视频 sample entry 是 hev1（ffmpeg mp4 封装器对
 * codec_tag=0 的裸 HEVC 默认标签），iPhone 媒体栈只认 hvc1 → MEDIA_ERR_SRC_NOT_SUPPORTED
 * （error code=4），手机回放黑屏；桌面 Chrome/Edge 容忍 hev1 所以桌面正常。</p>
 */
class IosRemuxServiceTest {

    @TempDir
    File tmp;

    private File write(String name, String content) throws Exception {
        File f = new File(tmp, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.ISO_8859_1));
        }
        return f;
    }

    @Test
    void mktagMatchesFfmpegSemantics() {
        // MKTAG(a,b,c,d) = a | b<<8 | c<<16 | d<<24：'hvc1' = 0x31637668
        assertEquals(0x31637668, Mp4SegmentRecorder.mktag('h', 'v', 'c', '1'));
        assertEquals(0x31637661, Mp4SegmentRecorder.mktag('a', 'v', 'c', '1'));
    }

    @Test
    void hev1NeedsRemuxHvc1Not() throws Exception {
        IosRemuxService svc = new IosRemuxService(null);
        String nul = "\u0000\u0000\u0000\u0000";
        assertTrue(svc.needsRemux(write("a.mp4",
                "ftypiso5....moov...trak.stsd....hev1" + nul + "hvcC....moofmdat")));
        assertFalse(svc.needsRemux(write("b.mp4",
                "ftypiso5....moov...trak.stsd....hvc1" + nul + "hvcC....moofmdat")));
        assertFalse(svc.needsRemux(write("c.mp4",
                "ftypiso5....moov...trak.stsd....avc1" + nul + "avcC....moofmdat")));
        assertFalse(svc.needsRemux(write("d.mp4", "gibberish without any box structure")));
        assertFalse(svc.needsRemux(new File(tmp, "missing.mp4")));
    }
}
