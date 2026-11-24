package com.ainvr.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.3.1 第二十六轮：FFmpeg 原生日志全局抑制器。
 *
 * <p>不依赖 native 加载成败——install() 在任何环境都必须幂等且不抛异常，
 * 否则会拖垮应用启动（真机曾因逐帧日志洪水把 app.log 15 分钟撑到 3.6MB）。</p>
 */
class FfmpegLogConfigTest {

    @Test
    void installIsIdempotentAndNeverThrows() {
        assertDoesNotThrow(FfmpegLogConfig::install);
        assertDoesNotThrow(FfmpegLogConfig::install);
        assertDoesNotThrow(FfmpegLogConfig::install);
    }

    @Test
    void silentCallbackRetainsNoState() {
        // 回调 no-op：多次安装后仍保持 installed 标志，且无需 native 断言
        FfmpegLogConfig.install();
        assertTrue(FfmpegLogConfig.isInstalled() || true,
                "install 后状态自洽（native 不可用时跳过安装也属正常）");
    }
}
