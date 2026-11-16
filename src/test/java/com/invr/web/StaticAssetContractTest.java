package com.invr.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fpk 内静态资源契约：飞牛 App（iOS WKWebView）返回键修复完全落在前端，
 * Java 单测覆盖不到 DOM 行为，这里至少保证打包进去的资源含全部关键标记、
 * 且版本号已同步升级，避免强刷后仍命中旧缓存。
 */
class StaticAssetContractTest {

    private static String resource(String name) throws Exception {
        try (InputStream in = StaticAssetContractTest.class.getResourceAsStream("/static/" + name)) {
            assertNotNull(in, "缺少静态资源 static/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void overlayBackNavigationAssetsArePackaged() throws Exception {
        String html = resource("index.html");
        String js = resource("app.js");
        String css = resource("style.css");

        assertTrue(html.contains("btnPreviewBack"), "预览弹层缺少应用内返回按钮");
        assertTrue(html.contains("btnPlayerBack"), "回放弹层缺少应用内返回按钮");
        assertTrue(html.contains("v=33"), "静态资源版本号未同步升级，WebView 会命中旧缓存");

        assertTrue(js.contains("addEventListener('popstate'"), "未接管宿主返回键");
        assertTrue(js.contains("enterOverlayHistory('preview')"), "预览未建立历史条目");
        assertTrue(js.contains("enterOverlayHistory('player')"), "回放未建立历史条目");
        assertTrue(js.contains("leaveOverlayHistory()"), "应用内关闭未回收历史条目");
        assertTrue(js.contains("pageshow"), "缺少 bfcache 恢复兜底");
        assertTrue(css.contains(".preview-back"), "缺少返回按钮样式");
    }
}
