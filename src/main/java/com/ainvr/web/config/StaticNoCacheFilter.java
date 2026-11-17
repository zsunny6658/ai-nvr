package com.ainvr.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 静态页面资源禁用浏览器/网关缓存。
 *
 * <p>fpk 升级后 index.html、app.js、style.css 会同时换新，但响应里只有
 * {@code Last-Modified} 没有 Cache-Control，浏览器会按启发式规则缓存；若资源 URL 上的
 * 版本号又恰好没变，就会出现「HTML 是新版、JS 仍是旧版」的错位 —— 界面上有新按钮，
 * 点击却没有任何反应，设置项也不渲染。这里对页面资源统一加 no-store，并配合每次
 * 发版递增 index.html 里的 ?v=，确保升级后一定拿到配套的新资源。</p>
 */
@Component
public class StaticNoCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        if (uri != null && (uri.endsWith("/") || uri.endsWith(".html")
                || uri.endsWith(".js") || uri.endsWith(".css"))) {
            res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            res.setHeader("Pragma", "no-cache");
            res.setDateHeader("Expires", 0L);
        }
        chain.doFilter(req, res);
    }
}
