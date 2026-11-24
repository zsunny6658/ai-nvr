package com.ainvr.codec;

import org.bytedeco.ffmpeg.avutil.Callback_Pointer_int_String_Pointer;
import org.bytedeco.javacpp.Pointer;

import static org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_callback;
import static org.bytedeco.ffmpeg.global.avutil.av_log_set_level;

/**
 * FFmpeg 原生日志全局抑制器（v2.3.1 第二十六轮）。
 *
 * <p>根因：{@code av_log_set_level()} 是<strong>进程全局</strong>设置，而
 * {@link SnapshotDecoder} 的静态块把它设回 {@code AV_LOG_WARNING}。每一次快照解码
 * 类初始化后，HLS muxer 的逐帧告警（"Stream 0 packet ... has duration 0"、
 * "Could not find ref with POC"）重新灌进 stderr → Spring 应用日志。真机日志里这类
 * 噪声占 95%（37268/39279 行），持续 ~4KB/s：既吃 CPU/磁盘 IO，又把日志文件在
 * 15 分钟内撑到 3.6MB，并在系统内存吃紧（swap 3.6/4GB）时放大卡顿，表现为
 * "手机和电脑都进不去"。</p>
 *
 * <p>修法：启动时安装一个<strong>全局 no-op 日志回调</strong>。回调一旦装上，
 * 任何后续 {@code av_log_set_level()} 都无法再让原生日志落到应用日志——从根上
 * 熄灭噪声源，而不是依赖每个调用点自觉保持level一致。业务侧关键事件（打开解码器、
 * 转码就绪、失败降级）本就用 SLF4J 单独记录，不受影响。</p>
 *
 * <p>注意：回调对象必须保持强引用，否则 GC 后 native 侧悬空指针会崩溃。</p>
 */
public final class FfmpegLogConfig {

    /** 必须保持强引用：native 侧持有的是裸函数指针。 */
    private static final Callback_Pointer_int_String_Pointer SILENT = new Callback_Pointer_int_String_Pointer() {
        @Override
        public void call(Pointer ptr, int level, String fmt, Pointer vl) {
            // 刻意留空：丢弃所有 FFmpeg 原生日志
        }
    };

    private static volatile boolean installed = false;

    private FfmpegLogConfig() {
    }

    /** 幂等安装；应在 Spring 上下文启动前调用一次。 */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        try {
            av_log_set_level(AV_LOG_QUIET);
            av_log_set_callback(SILENT);
            installed = true;
        } catch (Throwable t) {
            // native 库不可用（如纯单测 classpath 无 native）时静默跳过，绝不影响启动
            System.err.println("[ainvr] ffmpeg log suppress install skipped: " + t);
        }
    }

    /** 仅供测试：当前是否已安装。 */
    static boolean isInstalled() {
        return installed;
    }
}
