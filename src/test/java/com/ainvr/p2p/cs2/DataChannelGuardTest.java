package com.ainvr.p2p.cs2;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DataChannel 流失步防护（v2.3.1 第二十七轮）：
 * 2026-11-25 凌晨真机三路取流连环 Java heap OOM 的根因——BE32 长度前缀无上限 +
 * 重组缓冲无限增长。本组测试锁定防护行为：异常长度立即抛错断流（触发重连），
 * 而不是默默攒包到堆爆。
 */
class DataChannelGuardTest {

    private static byte[] be32(int v) {
        return new byte[] {
            (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v
        };
    }

    private static byte[] msg(int len) {
        byte[] m = new byte[len];
        for (int i = 0; i < len; i++) {
            m[i] = (byte) (i * 31);
        }
        return m;
    }

    @Test
    void giantLengthPrefixThrowsDesyncInsteadOfBuffering() {
        DataChannel dc = new DataChannel(0, 8);
        // 声称一条 20MB 的消息（超过 16MB 上限）：必须立刻抛错，不能开始攒包
        byte[] junk = new byte[64];
        byte[] head = be32(20 * 1024 * 1024);
        System.arraycopy(head, 0, junk, 0, 4);
        IOException ex = assertThrows(IOException.class, () -> dc.push(junk));
        assertTrue(ex.getMessage().contains("流失步"), "应标记为流失步：" + ex.getMessage());
    }

    @Test
    void desyncAccumulationBeyondCapThrows() throws Exception {
        DataChannel dc = new DataChannel(0, 8);
        // 前缀声称 15MB（低于单消息上限、绕过前缀检查），但让重组缓冲涨过 16MB 上限
        byte[] head = be32(15 * 1024 * 1024);
        byte[] first = new byte[4 + 1024 * 1024];
        System.arraycopy(head, 0, first, 0, 4);
        dc.push(first); // 开始攒
        byte[] chunk = new byte[1024 * 1024];
        assertThrows(IOException.class, () -> {
            for (int i = 0; i < 17; i++) {
                dc.push(chunk); // 1MB × 17 > 16MB 上限
            }
        });
    }

    @Test
    void popQueueCapStillThrowsWhenOverflowed() {
        DataChannel dc = new DataChannel(0, 4);
        byte[] one = new byte[4 + 8];
        byte[] head = be32(8);
        System.arraycopy(head, 0, one, 0, 4);
        assertThrows(IOException.class, () -> {
            for (int i = 0; i < 5; i++) {
                dc.push(one); // 第 5 条超过 pop 队列容量 4
            }
        });
    }

    @Test
    void normalFramingUnaffected() throws Exception {
        DataChannel dc = new DataChannel(0, 8);
        byte[] m1 = msg(16);
        byte[] m2 = msg(64);
        byte[] frame1 = new byte[4 + m1.length];
        System.arraycopy(be32(m1.length), 0, frame1, 0, 4);
        System.arraycopy(m1, 0, frame1, 4, m1.length);
        byte[] frame2 = new byte[4 + m2.length];
        System.arraycopy(be32(m2.length), 0, frame2, 0, 4);
        System.arraycopy(m2, 0, frame2, 4, m2.length);
        // 两条消息拆在两个包里投递（跨包重组仍正常）
        byte[] split1 = new byte[4 + 8];
        System.arraycopy(frame1, 0, split1, 0, split1.length);
        byte[] split2 = new byte[frame1.length - split1.length + frame2.length];
        System.arraycopy(frame1, split1.length, split2, 0, frame1.length - split1.length);
        System.arraycopy(frame2, 0, split2, frame1.length - split1.length, frame2.length);
        dc.push(split1);
        dc.push(split2);
        byte[] r1 = dc.pop();
        byte[] r2 = dc.pop();
        assertEquals(16, r1.length);
        assertArrayEquals(m1, r1);
        assertEquals(64, r2.length);
        assertArrayEquals(m2, r2);
    }

    // ===================== 第二十九轮：媒体通道丢旧帧背压 =====================

    private static byte[] framed(byte[] m) {
        byte[] f = new byte[4 + m.length];
        System.arraycopy(be32(m.length), 0, f, 0, 4);
        System.arraycopy(m, 0, f, 4, m.length);
        return f;
    }

    /**
     * 媒体通道队列溢出时丢最旧完整帧而不是断流——第二十七轮 32 条计数上限引发
     * 「pop buffer is full」断流回归（真机三台每天数百次重连，预览「连接不上」）。
     */
    @Test
    void mediaChannelDropsOldestInsteadOfDisconnecting() throws Exception {
        DataChannel dc = DataChannel.media(0, 4, 1024 * 1024);
        byte[] m1 = msg(16);
        byte[] m2 = msg(32);
        byte[] m3 = msg(48);
        byte[] m4 = msg(64);
        byte[] m5 = msg(80);
        dc.push(framed(m1));
        dc.push(framed(m2));
        dc.push(framed(m3));
        dc.push(framed(m4));
        // 第 5 条超容量 4：必须成功入队（丢 m1），绝不抛异常断流
        dc.push(framed(m5));
        byte[] r2 = dc.pop();
        byte[] r3 = dc.pop();
        byte[] r4 = dc.pop();
        byte[] r5 = dc.pop();
        assertArrayEquals(m2, r2, "最旧的 m1 应被丢弃");
        assertArrayEquals(m3, r3);
        assertArrayEquals(m4, r4);
        assertArrayEquals(m5, r5);
    }

    /** 字节上限同样触发丢旧帧：大帧场景（每条 ~100KB）不会按条数先爆。 */
    @Test
    void mediaChannelByteCapDropsOldest() throws Exception {
        int frameBytes = 64 * 1024;
        DataChannel dc = DataChannel.media(0, 64, frameBytes * 4L);
        byte[] big = msg(frameBytes);
        for (int i = 0; i < 10; i++) {
            dc.push(framed(big)); // 持续超 4 帧字节上限：全部应成功入队（丢旧）
        }
        // 队列里应只剩最后 ~4 帧且字节数不超上限
        int count = 0;
        byte[] r;
        long total = 0;
        while ((r = dc.pop(1)) != null) {
            assertEquals(frameBytes, r.length);
            total += r.length;
            count++;
        }
        assertTrue(count <= 5 && count >= 2, "应保留最近的若干帧，实际 " + count);
        assertTrue(total <= frameBytes * 5L, "字节上限应生效");
    }

    /** 控制通道（不可丢）保持满即抛错的防护语义。 */
    @Test
    void controlChannelStillThrowsWhenFull() {
        DataChannel dc = new DataChannel(0, 4);
        byte[] one = framed(msg(8));
        assertThrows(IOException.class, () -> {
            for (int i = 0; i < 5; i++) {
                dc.push(one);
            }
        });
    }
}
