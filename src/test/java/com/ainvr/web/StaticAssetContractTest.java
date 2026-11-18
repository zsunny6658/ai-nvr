package com.ainvr.web;

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
        assertTrue(html.contains("v=48"), "静态资源版本号未同步升级，WebView 会命中旧缓存");
        assertTrue(html.contains("id=\"pbSpeed\""), "缺少回放倍速控件");
        assertTrue(html.contains("id=\"pbLoop\""), "缺少回放循环控件");
        assertTrue(html.contains("id=\"backupImportFile\""), "缺少配置备份导入控件");
        assertTrue(html.contains("id=\"setAiEnabled\""), "缺少 AI 检测设置控件");

        assertTrue(js.contains("addEventListener('popstate'"), "未接管宿主返回键");
        assertTrue(js.contains("enterOverlayHistory('preview')"), "预览未建立历史条目");
        assertTrue(js.contains("enterOverlayHistory('player')"), "回放未建立历史条目");
        assertTrue(js.contains("leaveOverlayHistory()"), "应用内关闭未回收历史条目");
        assertTrue(js.contains("pageshow"), "缺少 bfcache 恢复兜底");
        assertTrue(css.contains(".preview-back"), "缺少返回按钮样式");
    }

    /** v1.2.19：ROI 排除区编辑器与事件跳转回放的关键标记。 */
    @Test
    void roiAndReplayJumpAssetsArePackaged() throws Exception {
        String html = resource("index.html");
        String js = resource("app.js");
        String css = resource("style.css");

        assertTrue(html.contains("roiModal"), "缺少 ROI 编辑弹层");
        assertTrue(html.contains("btnOpenRoi"), "缺少 ROI 入口按钮");
        assertTrue(html.contains("btnEvSnapshotReplay"), "快照大图缺少跳转回放按钮");
        assertTrue(js.contains("api/motion/zones/"), "缺少 ROI 读写接口调用");
        assertTrue(js.contains("openEvReplay"), "缺少事件跳转回放逻辑");
        assertTrue(js.contains("applyPbPending"), "回放页缺少事件定位待办处理");
        assertTrue(css.contains(".roi-stage"), "缺少 ROI 编辑器样式");
    }

    /** v1.2.21：多宫格预览、时间轴变动高亮、侦测时段计划、容量水位、事件合并计数。 */
    @Test
    void optimizationAssetsArePackaged() throws Exception {
        String html = resource("index.html");
        String js = resource("app.js");
        String css = resource("style.css");

        assertTrue(html.contains("gridModal"), "缺少多宫格弹层");
        assertTrue(html.contains("btnGridPreview"), "缺少多宫格入口按钮");
        assertTrue(html.contains("gridTour"), "缺少轮巡控件");
        assertTrue(js.contains("openGrid"), "缺少多宫格打开逻辑");
        assertTrue(js.contains("api/frame/"), "宫格应复用 JPEG 帧端点");
        assertTrue(html.contains("tlMotion"), "时间轴缺少变动高亮层");
        assertTrue(js.contains("renderMotionMarks"), "缺少变动高亮渲染");
        assertTrue(html.contains("setMotionSchedule"), "缺少侦测时段计划开关");
        assertTrue(html.contains("setWaterInput"), "缺少容量水位输入");
        assertTrue(js.contains("api/settings/water-level"), "缺少水位保存调用");
        assertTrue(js.contains("ev-repeat"), "缺少事件合并计数标记");
        assertTrue(css.contains(".live-grid"), "缺少宫格样式");
        assertTrue(css.contains(".tl-motion-mark"), "缺少变动高亮样式");
    }

    /** v1.2.22：目录选择器手动路径、原生全屏退出、RTSP 访问令牌的关键标记。 */
    @Test
    void manualDirAndFullscreenAndRtspTokenAssetsArePackaged() throws Exception {
        String html = resource("index.html");
        String js = resource("app.js");
        String css = resource("style.css");

        assertTrue(html.contains("dirManualPath"), "目录选择器缺少手动路径输入");
        assertTrue(html.contains("dirManualCreate"), "缺少「不存在时创建」开关");
        assertTrue(html.contains("setRtspTokenInput"), "缺少 RTSP 访问令牌输入");
        assertTrue(js.contains("goManualDir"), "缺少手动路径前往逻辑");
        assertTrue(js.contains("exitNativeFullscreen"), "缺少原生全屏退出");
        assertTrue(js.contains("teardownVideoEl"), "缺少统一视频流销毁");
        assertTrue(js.contains("saveRtspToken"), "缺少令牌保存逻辑");
        assertTrue(js.contains("rtspToken"), "缺少令牌接口字段");
        assertTrue(css.contains(".dir-manual"), "缺少手动路径行样式");
    }
}
