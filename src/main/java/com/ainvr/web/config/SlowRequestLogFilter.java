package com.ainvr.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * v2.3.1 第三十一轮（性能攻坚）：慢请求观测打点。
 *
 * <p>性能优化的前提是量化。此前「进入慢 / 加载慢 / 预览卡」只能靠体感描述，无法定位是
 * 服务端慢、传输慢还是前端渲染慢；本过滤器为每个 API 请求计时，超过阈值打 INFO 日志
 * （含方法、路径、耗时），真机验证时从日志直接看哪些接口拖了首屏的后腿。</p>
 *
 * <p>阈值取 {@value #SLOW_MS}ms：局域网内正常 API（含录像列表扫内存索引）应在几十 ms 内，
 * 超过即说明服务端有阻塞点（扫盘 / 云端调用 / 锁竞争），值得深挖。日志级别 INFO 且只在
 * 超阈值时打点，常态零开销、日志零噪音。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class SlowRequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SlowRequestLogFilter.class);

    /** 慢请求阈值（毫秒）。 */
    static final long SLOW_MS = 300;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (ms >= SLOW_MS && req.getRequestURI().startsWith("/api/")) {
                log.info("慢请求：{} {} 耗时 {}ms（状态 {}）",
                        req.getMethod(), req.getRequestURI(), ms, res.getStatus());
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // WebSocket 握手 (/ws/**) 与 SSE 长连接不计时；HLS/录像媒体流有专门的取流链路日志
        String uri = request.getRequestURI();
        return uri.startsWith("/ws/") || uri.startsWith("/hls/");
    }
}
