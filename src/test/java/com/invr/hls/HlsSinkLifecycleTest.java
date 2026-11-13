package com.invr.hls;

import com.invr.miss.MissPacket;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.2.15 崩溃防护的回归护栏。
 *
 * <p>线上两份 hs_err_pid 均显示：HLS 空闲回收时 purge() 释放 FFmpeg 原生上下文，
 * 而 sink-dispatch 线程仍在 av_interleaved_write_frame / avcodec_send_frame 里，
 * 导致 JVM SIGSEGV 退出。修复的核心不变量在此固定：包回调与 shutdown / purge
 * 必须是同一对象监视器上的 synchronized 方法，保证「释放原生上下文」不会与
 * 「在途写帧」重叠。</p>
 *
 * <p>这里只做结构断言，不实例化 HlsSink —— 其静态字段会加载 JavaCPP 原生库，
 * 本机 macOS 无 linux-x86_64 / macOS 对应 native 时无法在单测里初始化。</p>
 */
class HlsSinkLifecycleTest {

    @Test
    void packetCallbackAndTeardownShareTheSameMonitor() throws Exception {
        Method onPacket = HlsSink.class.getDeclaredMethod("onPacket", MissPacket.class, int.class);
        Method shutdown = HlsSink.class.getDeclaredMethod("shutdown");
        Method purge = HlsSink.class.getDeclaredMethod("purge");
        assertTrue(Modifier.isSynchronized(onPacket.getModifiers()), "onPacket 必须与 shutdown 互斥");
        assertTrue(Modifier.isSynchronized(shutdown.getModifiers()), "shutdown 必须持有对象锁");
        assertTrue(Modifier.isSynchronized(purge.getModifiers()), "purge 删除分片时不得与在途写帧并发");
    }
}
