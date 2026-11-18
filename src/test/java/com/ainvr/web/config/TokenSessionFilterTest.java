package com.ainvr.web.config;

import com.ainvr.web.service.TokenSessionManager;
import com.ainvr.web.service.TokenSessionManager.TokenSession;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 媒体令牌查询参数回归：__ainvr_t 必须能解析出令牌会话
 * （参数名漏改会导致预览/回放/快照全部 401——v2.2.0 首发的真实事故）。
 */
class TokenSessionFilterTest {

    private final TokenSessionManager manager = new TokenSessionManager();
    private final TokenSessionFilter filter = new TokenSessionFilter(manager);
    private final String realToken = manager.create().getToken();

    private TokenSession run(String queryParam) throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/hls/live/index.m3u8");
        if (queryParam != null) {
            req.setParameter(queryParam, realToken);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        return TokenSessionFilter.currentSession((MockHttpServletRequest) chain.getRequest());
    }

    @Test
    void newParamNameResolvesSession() throws Exception {
        assertNotNull(run(TokenSessionFilter.QUERY_PARAM), "新参数名 __ainvr_t 应解析出会话");
    }

    @Test
    void noParamResolvesNothing() throws Exception {
        assertNull(run(null), "无令牌参数时不应有会话（AccessInterceptor 负责 401）");
    }

    @Test
    void bearerHeaderHasPriorityOverQueryParam() throws Exception {
        TokenSessionManager manager = new TokenSessionManager();
        TokenSessionFilter filterWithRealManager = new TokenSessionFilter(manager);
        String headerToken = manager.create().getToken();
        String queryToken = manager.create().getToken();

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/hls/live/index.m3u8");
        req.addHeader("Authorization", "Bearer " + headerToken);
        req.setParameter(TokenSessionFilter.QUERY_PARAM, queryToken);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filterWithRealManager.doFilter(req, resp, chain);
        // 头通道与查询参数同时携带时，应取头里的令牌（而非查询参数里的）
        assertNotNull(TokenSessionFilter.currentSession((MockHttpServletRequest) chain.getRequest()));
        assertEquals(headerToken,
                TokenSessionFilter.currentSession((MockHttpServletRequest) chain.getRequest()).getToken());
    }
}
