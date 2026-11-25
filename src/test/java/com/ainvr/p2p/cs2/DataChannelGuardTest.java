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
}
