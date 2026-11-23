package com.ainvr.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 静态页面资源缓存策略（v2.3.1 打开提速）。
 *
 * <p>旧实现把所有页面资源一律 {@code no-store}——每次打开页面都全量重新下载 app.js
 * （约 300KB），在 NAS 上行带宽有限或经外网中继访问时要等数秒，是「页面打开慢」的主体。
 * 现按资源类型分级：</p>
 *
 * <ul>
 *   <li><b>HTML</b>：{@code no-cache}——每次都向服务器校验（配合 Last-Modified 304 秒回），
 *       保证 fpk 升级后第一时间拿到新入口；</li>
 *   <li><b>JS/CSS 且 URL 带版本号 ?v=N</b>：{@code public, max-age=31536000, immutable}——
 *       发版时 index.html 里的 ?v= 递增，URL 变化天然穿透缓存，不存在「新旧错位」；
 *       日常打开页面零下载（内存/磁盘缓存直出）；</li>
 *   <li><b>JS/CSS 无版本号</b>：{@code no-cache}——校验缓存（304），不做 no-store。</li>
 * </ul>
 *
 * <p>原先 no-store 防的「HTML 新版 + JS 旧版错位」在 immutable 方案下依然成立：旧版 JS 的
 * URL（旧 v=）与新 HTML 无关，浏览器缓存的是旧 URL；新 HTML 引用新 URL 必然回源。</p>
 */
@Component
public class StaticNoCacheFilter extends OncePerRequestFilter {

    private static final String IMMUTABLE = "public, max-age=31536000, immutable";
    private static final String NO_CACHE = "no-cache";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = req.getRequestURI();
        if (uri != null && (uri.endsWith("/") || uri.endsWith(".html"))) {
            res.setHeader("Cache-Control", NO_CACHE);
        } else if (uri != null && (uri.endsWith(".js") || uri.endsWith(".css"))) {
            // 版本号由发版流程保证递增（StaticAssetContractTest 断言），可安全长缓存
            String q = req.getQueryString();
            boolean versioned = q != null && q.contains("v=");
            res.setHeader("Cache-Control", versioned ? IMMUTABLE : NO_CACHE);
        }
        chain.doFilter(req, res);
    }
}
