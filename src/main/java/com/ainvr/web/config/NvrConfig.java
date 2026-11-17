package com.ainvr.web.config;

import com.ainvr.hub.StreamHub;
import com.ainvr.hub.CameraConnector;
import com.ainvr.rtsp.RtspServer;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import com.ainvr.store.RecordingStateStore;
import com.ainvr.store.SessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 核心单例装配。{@link StreamHub} 为进程级单例，是"同一摄像头只占一条上游"硬约束在
 * Web 架构下的落点：RTSP、HLS 预览、MP4 录像全部订阅这同一个实例。
 */
@Configuration
public class NvrConfig {

    @Bean
    public SessionStore sessionStore() {
        return new SessionStore();
    }

    @Bean
    public RecordingStateStore recordingStateStore() {
        return new RecordingStateStore();
    }

    @Bean
    public AppSettingsStore appSettingsStore() {
        return new AppSettingsStore();
    }

    /** 告警事件库：启动时重放 events.jsonl 恢复内存索引，不引入数据库依赖。 */
    @Bean
    public EventStore eventStore() {
        return new EventStore();
    }

    @Bean(destroyMethod = "shutdown")
    public StreamHub streamHub() {
        return new StreamHub();
    }

    @Bean
    public RtspServer rtspServer(NvrProperties props, StreamHub hub, CameraConnector connector,
                                 AppSettingsStore appSettingsStore) {
        // 启动端口：Web 设置（settings.json）优先，缺省回落配置文件 invr.rtsp-port
        Integer custom = appSettingsStore.rtspPort();
        int port = (custom != null && custom > 0) ? custom : props.getRtspPort();
        // 启动令牌：Web 设置优先，缺省回落配置文件 invr.rtsp-token（v1.2.22）
        String savedToken = appSettingsStore.rtspToken();
        String token = savedToken != null ? savedToken : props.getRtspToken();
        return new RtspServer(port, hub, connector, token);
    }
}
