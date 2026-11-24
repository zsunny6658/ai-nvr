package com.ainvr.web;

import com.ainvr.codec.FfmpegLogConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * iNvr Web 服务入口。
 *
 * <p>启动后同时提供：
 * <ul>
 *   <li>HTTP Web 控制台 + REST API（默认 8080）：登录/设备/预览/录像全流程；</li>
 *   <li>内嵌 RTSP 下行服务（默认 8554）：{@code rtsp://主机:8554/<did>}。</li>
 * </ul>
 * RTSP、网页 HLS 预览、MP4 录像三类下行共享同一个 {@code StreamHub} 单例，
 * 同一摄像头无论被多少消费者使用都只占一条小米 CS2 上游连接。
 */
@SpringBootApplication(scanBasePackages = "com.ainvr")
@ConfigurationPropertiesScan("com.ainvr.web")
public class NvrWebApplication {

    public static void main(String[] args) {
        // v2.3.1 第二十六轮：在任何 ffmpeg native 调用之前熄灭逐帧日志洪水。
        // 放在 Spring 启动前，避免早于本类的静态初始化（如 SnapshotDecoder）重新打开告警。
        FfmpegLogConfig.install();
        SpringApplication.run(NvrWebApplication.class, args);
    }
}
