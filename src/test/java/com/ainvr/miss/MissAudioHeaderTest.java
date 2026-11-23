package com.ainvr.miss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 对讲上行链路协议回归锁（v2.3.1）。
 *
 * <p>MISS 音频包 32 字节头布局（对齐 go2rtc internal/xiaomi/miss）：
 * offset 0 = 加密载荷长度 LE32；offset 4 = 编解码 ID LE32；
 * offset 16 = 64bit LE 墙钟毫秒（字段宽必须 8 字节——此前误写 32 位，
 * 高 4 字节恒 0 且时间戳截断）；其余字节恒 0。</p>
 */
class MissAudioHeaderTest {

    private static long readLe32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static long readLe64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    @Test
    void headerLayoutMatchesGo2rtcContract() {
        byte[] h = MissClient.buildAudioHeader(320, MissCommands.CODEC_PCMA, 0x18E2C0F1A2BL);
        assertEquals(32, h.length, "包头必须 32 字节");
        assertEquals(320, readLe32(h, 0), "offset 0 应为载荷长度 LE32");
        assertEquals(MissCommands.CODEC_PCMA, readLe32(h, 4), "offset 4 应为编解码 ID LE32");
        assertEquals(0x18E2C0F1A2BL, readLe64(h, 16), "offset 16 应为 64bit LE 时间戳（不得截断）");
        for (int i = 8; i < 16; i++) {
            assertEquals(0, h[i], "offset 8..15 应恒为 0，实际 offset=" + i);
        }
        for (int i = 24; i < 32; i++) {
            assertEquals(0, h[i], "offset 24..31 应恒为 0，实际 offset=" + i);
        }
    }

    @Test
    void largeTimestampIsNotTruncated() {
        // 2026 年前后的毫秒值，高位非零——若按 32 位写入会丢失高位
        long ts = 1_792_000_000_000L; // 约 2026-10-15
        byte[] h = MissClient.buildAudioHeader(640, MissCommands.CODEC_PCMA, ts);
        assertEquals(ts, readLe64(h, 16), "64bit 时间戳必须完整往返");
    }

    @Test
    void talkPathCodecIdsAndChunkSizes() {
        // 对讲上行两种载荷（MissCommands.speakerCodecFor）：绝大多数机型 G.711 A-law
        //（CODEC_PCMA，320B/40ms）；hlc6 线性 PCM（CODEC_PCM，640B/40ms）
        assertEquals(1027, MissCommands.CODEC_PCMA);
        assertEquals(1024, MissCommands.CODEC_PCM);
        byte[] alaw = MissClient.buildAudioHeader(320, MissCommands.CODEC_PCMA, 0L);
        byte[] pcm = MissClient.buildAudioHeader(640, MissCommands.CODEC_PCM, 0L);
        assertEquals(320, readLe32(alaw, 0));
        assertEquals(640, readLe32(pcm, 0));
        assertArrayEquals(new byte[8], java.util.Arrays.copyOfRange(alaw, 8, 16));
    }
}
