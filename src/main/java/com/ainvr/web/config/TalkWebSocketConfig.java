package com.ainvr.web.config;

import com.ainvr.web.api.AccessController;
import com.ainvr.web.config.TokenSessionFilter;
import com.ainvr.web.service.LiveWebSocketHandler;
import com.ainvr.web.service.TalkWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 装配：
 * <ul>
 *   <li>{@code /ws/talk/{did}}：对讲喊话上行（注册通配 {@code /ws/talk/*}）；</li>
 *   <li>{@code /ws/live/{did}}：预览直播 fMP4 推流（v2.3.1 第二十六轮，iOS
 *       ManagedMediaSource via srcObject 的数据源）。</li>
 * </ul>
 *
 * <p>鉴权复用访问门禁体系：{@link TokenSessionFilter} 是全局过滤器，对 WS 升级请求同样生效，
 * 浏览器 WebSocket 无法携带自定义请求头，令牌以 {@code ?__ainvr_t=} 查询参数携带
 * （与原生 HLS 播放一致）；握手拦截器里做与 {@code AccessInterceptor} 相同的解锁校验。</p>
 */
@Configuration
@EnableWebSocket
public class TalkWebSocketConfig implements WebSocketConfigurer {

    private final TalkWebSocketHandler talkHandler;
    private final LiveWebSocketHandler liveHandler;

    public TalkWebSocketConfig(TalkWebSocketHandler talkHandler, LiveWebSocketHandler liveHandler) {
        this.talkHandler = talkHandler;
        this.liveHandler = liveHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(talkHandler, "/ws/talk/*")
                .addInterceptors(new GateHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
        registry.addHandler(liveHandler, "/ws/live/*")
                .addInterceptors(new GateHandshakeInterceptor())
                .setAllowedOriginPatterns("*");
    }

    /**
     * 握手门禁：与 {@code AccessInterceptor} 同语义——令牌会话未解锁则拒绝升级。
     * TokenSessionFilter 已在过滤器链上解析出会话并挂到请求属性，这里直接读取。
     */
    private static class GateHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletReq) {
                var session = TokenSessionFilter.currentSession(servletReq.getServletRequest());
                return AccessController.isUnlocked(session);
            }
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            // 无需处理
        }
    }
}
