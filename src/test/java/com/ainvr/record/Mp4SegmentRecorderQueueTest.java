package com.ainvr.record;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 录像帧队列背压回归测试（OOM 根因守护）：
 * 真机 2026-11-27 02:12 堆转储显示 67 万个队列节点 + 902MB byte[] 积压 —— 无界
 * LinkedBlockingQueue 在录制线程异常退出后无限入队所致。本测试锁定有界行为。
 */
class Mp4SegmentRecorderQueueTest {

    private static final Class<?> QCLASS;
    private static final Class<?> VFRAME;
    static {
        try {
            QCLASS = Class.forName("com.ainvr.record.Mp4SegmentRecorder$BoundedMediaQueue");
            VFRAME = Class.forName("com.ainvr.record.Mp4SegmentRecorder$VideoFrame");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Object newQueue(long maxBytes) throws Exception {
        Constructor<?> c = QCLASS.getDeclaredConstructor(long.class);
        c.setAccessible(true);
        return c.newInstance(maxBytes);
    }

    private static Object frame(int bytes) throws Exception {
        Constructor<?> c = VFRAME.getDeclaredConstructor(long.class, byte[].class);
        c.setAccessible(true);
        return c.newInstance(0L, new byte[bytes]);
    }

    private static void offer(Object q, Object f, int bytes) throws Exception {
        Method m = QCLASS.getDeclaredMethod("offer", Object.class, int.class);
        m.setAccessible(true);
        m.invoke(q, f, bytes);
    }

    private static long dropped(Object q) throws Exception {
        Method m = QCLASS.getDeclaredMethod("dropped");
        m.setAccessible(true);
        return (long) m.invoke(q);
    }

    private static Object poll(Object q) throws Exception {
        Method m = QCLASS.getDeclaredMethod("poll");
        m.setAccessible(true);
        return m.invoke(q);
    }

    @Test
    void boundedByBytesDropsOldest() throws Exception {
        // 容量 10 字节：塞 3 个各 4 字节的帧 → 最旧的必须被丢弃，总量永不超过 10
        Object q = newQueue(10);
        Object f1 = frame(4), f2 = frame(4), f3 = frame(4);
        offer(q, f1, 4);
        offer(q, f2, 4);
        offer(q, f3, 4);
        assertEquals(1, dropped(q), "应丢弃 1 个旧帧");
        assertEquals(f2, poll(q), "最旧帧应被丢弃");
        assertEquals(f3, poll(q));
        assertNull(poll(q));
    }

    @Test
    void oversizedFrameRejected() throws Exception {
        Object q = newQueue(10);
        offer(q, frame(11), 11);
        assertEquals(1, dropped(q));
        assertNull(poll(q));
    }

    @Test
    void byteCountAccurateAfterPoll() throws Exception {
        // 关键：poll/take 必须扣减字节数，否则队列会被永久误判为满
        Object q = newQueue(10);
        Object a = frame(6), b = frame(6);
        offer(q, a, 6);
        assertEquals(a, poll(q));
        offer(q, b, 6); // 若字节未扣减，这里会错误丢帧
        assertEquals(0, dropped(q));
        assertEquals(b, poll(q));
    }

    @Test
    void zeroByteControlFrameDoesNotTriggerDrop() throws Exception {
        // EOF 控制项 bytes=0：不允许因为队列满而把媒体帧全部丢光
        Object q = newQueue(4);
        Object media = frame(4), eof = frame(0);
        offer(q, media, 4);
        offer(q, eof, 0);
        assertEquals(0, dropped(q));
        assertEquals(media, poll(q));
        assertEquals(eof, poll(q));
    }
}
