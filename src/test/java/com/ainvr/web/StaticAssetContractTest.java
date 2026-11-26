package com.ainvr.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(html.contains("v=93"), "静态资源版本号未同步升级，WebView 会命中旧缓存");
        // v2.3.1 第二十七轮回归锁：iPhone MMS-VOD 内联回放（根治回放系统全屏）
        assertTrue(js.contains("function mountVodMms") && js.contains("function stopVodMms"),
                "回放/录像弹层必须支持 MMS 渐进回放（直链在部分 WKWebView 宿主里 playsinline 被忽略 → 全屏）");
        assertTrue(js.contains("state.vodSess"), "MMS 回放会话需有全局槽位（换段/关闭时停止）");
        assertTrue(js.contains("if (pb.mms) return;"), "MMS 回放下预载/提前交接必须让位（ended 自动接续）");
        assertTrue(js.contains("pb_vod_fail"), "MMS 回放失败须上报诊断并回退直链");
        // v2.3.1 第二十八轮回归锁：对讲按机型选 OPUS（039a01/079ac1），CW400(hlc8) 回归 PCMA
        assertTrue(js.contains("OPUS_TALK_MODELS"), "前端手势内采样率决策须与服务端 speakerCodecFor 同表");
        assertTrue(js.contains("'chuangmi.camera.039a01': 1") && js.contains("'chuangmi.camera.079ac1': 1")
                        && js.contains("'chuangmi.camera.72ac1': 1"),
                "OPUS 机型表须包含 039a01 / 079ac1 / 72ac1");
        assertTrue(!js.contains("'isa.camera.hlc8': 1"), "CW400(hlc8) 对讲须为 PCMA，不得留在 OPUS 机型表");
        // v2.3.1 第二十七轮回归锁：MMS 首包 1 片（真机实锤 2 片冷启动 13s > 12s 截止被误判直播中断）
        assertTrue(js.contains("mms-retry"), "MMS 失败须先原路重试一次再降级（blob 直链在 iPhone 必全屏）");
        assertTrue(js.contains("autoFallbackToCompat('直播推流中断"), "MMS 重试仍失败应降级兼容画面而非 blob 全屏");
        // v2.3.1 第二十六轮回归锁：iPhone 预览成熟方案（ManagedMediaSource via srcObject）
        assertTrue(js.contains("function mountPreviewMms") && js.contains("video.srcObject = ms"),
                "iPhone 纯 MMS 环境必须走 srcObject 挂接（video.src=blob: 是 AVPlayer 全屏路径）");
        assertTrue(js.contains("ws/live/") , "MMS 数据源应为 /ws/live/{did} WS 推流");
        assertTrue(js.indexOf("if (didM && 'ManagedMediaSource' in window") < js.indexOf("if (window.Hls && Hls.isSupported())"),
                "纯 MMS 环境（iPhone）判定必须先于 hls.js（其 isSupported 在 iPhone 亦为真）");
        assertTrue(js.contains("function stopLiveMms") && js.contains("stopLiveMms(); // v2.3.1 第二十六轮"),
                "换路径/关预览必须停掉 MMS 会话（防僵尸 WS）");
        // v2.3.1 第二十六轮回归锁：对讲成熟方案（手势内 AudioContext + AudioWorklet + 静音保活）
        assertTrue(js.contains("talk.ctx = new Ctx("), "AudioContext 必须在用户手势内创建（ws.onmessage 里创建会 suspended → 0 帧）");
        assertTrue(js.contains("ainvr-talk-capture"), "采集应优先 AudioWorklet（ScriptProcessor 已废弃且 WKWebView 节流）");
        assertTrue(js.contains("silence.fill(0xD5)"), "静默期应补发 A-law 静音帧保持设备扬声器常开（>1s 无音频自动关）");
        // v2.3.1 真机验证第五轮：主题快捷按钮两态 + 选目录自动授权 + 内置权限面板
        assertTrue(js.contains("trimAuthorizePath"), "缺少选目录自动授权逻辑");
        assertTrue(js.contains("authorizeUserFile"), "缺少用户空间授权调用");
        assertTrue(html.contains("btnOpenAppPerm"), "存储卡片缺少系统授权页直达按钮");
        assertTrue(html.contains("permStatusText"), "存储卡片缺少权限状态检测");
        assertTrue(css.contains(".set-perm"), "缺少内置权限面板样式");
        // v2.3.1 真机验证第十七轮热修：权限面板不再 sdk-only（独立浏览器也显示状态与指引），
        // 仅「打开系统授权页」按钮保留飞牛宿主专属
        assertFalse(html.contains("set-perm sdk-only"), "权限面板不应整体 sdk-only（独立浏览器会整块看不到）");
        assertTrue(html.contains("btn tiny sdk-only\" id=\"btnOpenAppPerm\""), "打开系统授权页按钮应保留 sdk-only");
        assertTrue(js.contains("refreshPermStatus();"), "进入存储分组应无条件刷新权限状态（不限飞牛宿主）");
        assertTrue(js.contains("应用中心 → 本应用 → 设置 → 存储管理"), "独立浏览器未授权时应有文字授权指引");
        // v2.3.1 真机验证第十八轮回归锁
        assertTrue(js.contains("pcm = -pcm - 1"), "A-law 编码负数应为 -pcm-1（旧版 -pcm-8 负半周失真致杂音）");
        assertFalse(js.contains("-pcm - 8"), "A-law 编码不得残留旧版 -pcm-8 分支");
        assertTrue(js.contains("guardNativeVideoFs"), "应有原生视频全屏拦截转换（手机莫名全屏+无云台浮层）");
        assertTrue(js.contains("disablePictureInPicture"), "预览视频应禁画中画");
        assertTrue(css.contains("padding: 14px 16px 0"), "设备卡文字区应留足内边距（不贴边）");
        assertFalse(js.contains("'light' ? 'auto' : 'dark'"), "侧栏主题快捷按钮不应循环到跟随系统");
        // v2.3.1 第四轮性能修复：解锁懒加载录像列表 + 分组增量渲染 + 回放换段预载修复
        assertTrue(js.contains("maybeSwapEarly"), "缺少回放边界提前交接逻辑");
        assertTrue(js.contains("idle.preload = 'auto'"), "待命元素应显式 auto 预缓冲");
        assertTrue(js.contains("data-rmore"), "录像文件列表缺少「加载更多」增量渲染");
        assertTrue(js.contains("recListCurrent"), "录像列表缺少当前全量数据缓存");
        // v2.3.1 真机验证第二轮：浏览直调系统选择器 + 休眠徽标 + 主题跟随系统默认
        assertTrue(js.contains("browseDirFlow"), "缺少目录浏览直调系统选择器逻辑");
        assertTrue(js.contains("applyPickedDir"), "缺少选定目录落输入框逻辑");
        assertTrue(js.contains("lanReachable"), "设备列表缺少局域网可达性字段");
        // v2.3.1 第三轮补丁：休眠判定以云端 MIoT 休眠属性为准，徽标可点击唤醒
        assertTrue(js.contains("sleeping === true"), "休眠徽标应依据云端 sleeping 属性");
        assertTrue(js.contains("toggleSleep"), "缺少点击休眠徽标唤醒 / 卡片休眠下发逻辑");
        assertTrue(js.contains("net sleep"), "缺少休眠徽标样式标记");
        // v2.3.1 真机验证第九轮：米家式休眠交互（卡顶点击唤醒 + 次行常驻切换 + 钉住防翻转）
        assertTrue(js.contains("cam-sleep-play"), "休眠卡顶应有 hover 播放覆盖层");
        assertTrue(js.contains("cam-media-sleep"), "休眠态卡顶快照区应有独立标记类");
        assertTrue(js.contains("d.sleeping = resp.sleeping"), "下发后应直接用 POST 返回状态渲染，不得立即探测覆盖");
        assertTrue(!js.contains("d.sleeping = p.sleeping"), "下发后不应再用实时探测旧值覆盖状态");
        assertTrue(js.contains("(sleeping ? 'wake' : 'sleep')"), "次行按钮应按休眠态切换 wake/sleep 动作");
        // v2.3.1 真机验证第十轮续：5s 轮询不得盲改休眠徽标 + 休眠中徽标/快照降级
        assertTrue(js.contains("net.outerHTML = html"), "轮询应经 onlineBadge 重生成徽标，不得盲写在线/离线");
        assertTrue(js.contains("data-sleeping"), "卡片应带休眠态标记供轮询检测整卡重渲染");
        assertTrue(js.contains("badge off\">休眠中"), "休眠中应显示「休眠中」徽标而非预览中/录像中");
        assertTrue(js.contains("dev.sleeping === true) { img.hidden = false; return; }"), "休眠中应跳过快照轮询保留最近画面");
        assertTrue(js.contains("(pointer: coarse)"), "触屏端应检测粗指针以禁用预览原生控件");
        // v2.3.1 第十四轮：对讲 16k 主路径与兜底插值互斥（无声回归锁）
        assertTrue(js.contains("rate === 16000"), "对讲 16k 主路径应与兜底插值互斥（无声回归锁）");
        assertTrue(js.contains("pushTalkChunk(bytes, 320)") && js.contains("pushTalkChunk(new Uint8Array(pcm.buffer), 640)"),
                "对讲分块大小应为 A-law 320B / PCM 640B（40ms）");
        assertTrue(js.contains("talk.pacer = setInterval") && js.contains("}, 40);"),
                "对讲应有 40ms 匀速节奏器");
        // v2.3.1 第十五轮：统计条压缩 + 可点击 + 休眠计数；筛选 chips；页脚/导航移动端
        assertTrue(js.contains("dev.sleeping || 0"), "统计副行应包含休眠台数");
        assertTrue(js.contains("'未处理 ' + (ev.unhandled == null ? 0 : ev.unhandled) + ' 条'"),
                "事件卡副行应显示未处理数");
        assertTrue(html.contains("data-goto=\"cameras\"") && html.contains("data-goto=\"events\"")
                        && html.contains("data-goto=\"playback\""),
                "统计卡应可点击直达对应页面");
        assertTrue(html.contains("id=\"devFilter\"") && html.contains("data-filter=\"sleeping\""),
                "应有摄像头筛选 chips（含休眠中）");
        assertTrue(html.contains("data-filter=\"awake\"") && js.contains("f === 'awake'"),
                "筛选应包含「未休眠」选项（与休眠对应）");
        assertTrue(js.contains("function deviceMatchesFilter"), "缺少筛选匹配函数");
        assertTrue(js.contains("updateFilterChips()"), "筛选 chips 计数应随刷新更新");
        assertTrue(css.contains(".dev-filter") && css.contains(".chip.active"), "缺少筛选 chips 样式");
        assertTrue(css.contains(".stat-link"), "统计卡应有点击态样式");
        assertTrue(css.contains(".stat-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));"),
                "统计卡应为 4 列等宽布局（6 列留空两格问题）");
        // v2.3.1 第二十轮回归锁：手机原生全屏根除 + 起播提速
        assertTrue(html.contains("id=\"videoShield\"") && css.contains(".video-shield"),
                "视频触摸屏障层（防 X5/夸克手势劫持进自带全屏）必须存在");
        assertTrue(js.contains("webkitbeginfullscreen"),
                "iOS 前置全屏事件拦截（webkitbeginfullscreen）缺失");
        // v2.3.1 第二十一轮回归锁：伪全屏死锁根修（iPhone 真机实测点预览被卡全屏）
        {
            int guardIdx = js.indexOf("function guardNativeVideoFs");
            int exitIdx = js.indexOf("function exitPreviewFullscreen");
            assertTrue(guardIdx >= 0 && exitIdx > guardIdx, "guardNativeVideoFs 应存在");
            String guardBlock = js.substring(guardIdx, exitIdx);
            assertFalse(guardBlock.contains("classList.add('fs-fake')"),
                    "原生全屏拦截禁止自动转伪全屏（无退出按钮困死用户，真机实锤）");
            assertTrue(js.contains("$('btnFsExit').hidden = !isFsActive()"),
                    "伪全屏（fs-fake）必须有可见退出按钮");
        }
        // v2.3.1 第二十一轮回归锁：米家下拉随设备列表刷新 / 时间刻度抽稀 / 回放诊断与续播加固
        assertTrue(js.contains("refreshMiotDevSelect();\n            if (forceRefresh || structureChanged)"),
                "设备到达后应刷新米家设备控制下拉（手机上先开设置后到设备显示空）");
        assertTrue(js.contains("labelEvery") && js.contains("majorSpacingPx"),
                "回放时间刻度标签应按容器宽度动态抽稀（手机端重叠）");
        assertTrue(js.contains("回放视频加载失败"),
                "回放视频加载错误应有可见提示（手机黑屏诊断）");
        assertTrue(js.contains("回放未自动续播"),
                "续播被浏览器拦截时应有可见提示（不再静默吞拒）");
        assertTrue(js.contains("startFragPrefetch: true"),
                "hls.js 应开启首分片预取（起播提速）");
        assertTrue(js.contains("segCount(txt) >= 1"),
                "原生 HLS 预热应等首个分片即起播（等 3 片白等约 10 秒）");
        // v2.3.1 第二十二轮回归锁：iPhone 全屏劫持根修（MSE 优先 + 退出后恢复播放）+ 明文应答 + 客户端诊断
        {
            int mountIdx = js.indexOf("function mountPreviewPlayer");
            int nativeIdx = js.indexOf("v2.3.1 对讲喊话");
            assertTrue(mountIdx >= 0 && nativeIdx > mountIdx, "mountPreviewPlayer 应存在");
            String mountBlock = js.substring(mountIdx, nativeIdx);
            assertFalse(mountBlock.contains("isChromium()"),
                    "预览挂载不得再用 isChromium 门槛排除 iOS MSE（iPhone 原生 HLS 被系统播放器接管，真机实锤）");
            assertTrue(mountBlock.contains("Hls.isSupported()"),
                    "hls.js 可用即应优先 MSE（iOS 17.1+ 走 ManagedMediaSource，应用内播放无系统全屏）");
        }
        // v2.3.1 第二十三轮回归锁：MMS 激活条件 + 接受系统播放器策略 + hev1 根因
        assertTrue(js.contains("disableRemotePlayback = true"),
                "iPhone 上 ManagedMediaSource 必须先禁用 RemotePlayback 才会激活（WWDC 2023 官方要求）");
        assertTrue(js.contains("preview_mount"),
                "预览挂载路径应上报诊断（MMS/MSE 可用性决定 iPhone 链路，remote 取证）");
        assertTrue(js.contains("webkitendfullscreen") && js.contains("readyState < 2"),
                "iOS 退出系统全屏后应恢复内联并在无画面时降级兼容画面（死循环根修）");
        {
            // 只锁预览 begin/end 事件块内不得对抗性退出（预览关闭路径的
            // exitNativeFullscreen 是防僵尸全屏的合法清理，不受限）
            int beginIdx = js.indexOf("preview：进入系统全屏（接受系统播放器）");
            int degradeIdx = js.indexOf("ios_fs_degrade");
            assertTrue(beginIdx >= 0 && degradeIdx > beginIdx, "预览 iOS 全屏处理块应存在");
            assertFalse(js.substring(beginIdx, degradeIdx).contains("webkitExitFullscreen"),
                    "禁止在 begin/end 全屏事件里对抗性退出系统播放器（退出→重播→再进死循环，client-diag 实锤）");
        }
        assertTrue(js.contains("remux=1"),
                "回放 code=4 应自动带 remux=1 重试（存量 hev1 段 iPhone 不识别）");
        assertTrue(js.contains("reportClientDiag('ios_fs_begin'"),
                "iOS 全屏事件应上报 client-log（远程定位真机问题）");
        assertTrue(js.contains("majorSpacingPx < 76") && js.contains("needThin"),
                "时间刻度抽稀阈值应提升至 76px 且抽稀时使用 HH:MM 短标签（iPhone 仍拥挤）");
        assertTrue(js.contains("api/diagnostics/client-log"),
                "前端应有客户端诊断上报通道");
        // v2.3.1 第二十五轮回归锁：无 MSE 环境的预览改走 live.mp4 滚动播放（回放同款 fMP4）
        {
            int mountIdx = js.indexOf("function mountPreviewPlayer");
            int nativeIdx = js.indexOf("function mountNativeHls");
            assertTrue(mountIdx >= 0 && nativeIdx > mountIdx, "mountPreviewPlayer / mountNativeHls 应存在");
            String mountBlock = js.substring(mountIdx, nativeIdx);
            assertTrue(mountBlock.indexOf("startLiveMp4Roll") < mountBlock.indexOf("canPlayType('application/vnd.apple.mpegurl')"),
                    "无 MSE 时应先试 live.mp4 滚动播放，原生 HLS（会被系统播放器劫持）只作兜底");
            assertTrue(js.contains("/live.mp4?"), "live.mp4 滚动取片端点应被调用");
            assertTrue(js.contains("X-Live-To"), "滚动会话应跟踪服务端分片区间（ended 后携带 from 续取）");
            assertTrue(js.contains("stopLiveRoll()"), "换路径/关预览/切兼容画面必须停掉滚动会话（防僵尸续取）");
            assertTrue(js.contains("live_mp4_giveup"), "连续取片失败应上报诊断并退回原生 HLS 兜底");
        }
        // v2.3.1 第十九轮回归锁：.stat-link 只加交互态，禁止再重置卡片外观——
        // 此前 background:none/border:none/padding:inherit 把 .stat 卡片底色边框
        // 内边距全数覆盖，统计区退化成裸文字贴边（真机截图实锤）
        {
            int statIdx = css.indexOf(".stat-link {");
            int miotIdx = css.indexOf(".miot-rows");
            assertTrue(statIdx >= 0 && miotIdx > statIdx, "stat-link 样式块应存在");
            String statLinkBlock = css.substring(statIdx, miotIdx);
            assertFalse(statLinkBlock.contains("background: none"), "stat-link 禁止重置卡片底色（会盖掉 .stat 卡片外观）");
            assertFalse(statLinkBlock.contains("border: none"), "stat-link 禁止重置边框");
            assertFalse(statLinkBlock.contains("padding: inherit"), "stat-link 禁止继承内边距（继承到 0 = 文字贴边）");
        }
        assertTrue(css.contains("flex-wrap: wrap; padding: 6px;"),
                "手机设置导航应换行展示全部分组（横向滑动藏菜单问题）");
        assertTrue(css.contains(".app-footer { padding-bottom: calc(72px + env(safe-area-inset-bottom, 0px)); }"),
                "移动端页脚下让位规则必须位于文件末尾（此前被 .app-footer 简写覆盖）");
        assertTrue(js.contains("cam-sleep-art") && js.contains("休眠中 · 点击唤醒"), "休眠卡应显示月亮占位图而非快照");
        assertTrue(js.contains("pv.controls = !coarsePtr && !hasTouch"),
                "预览原生控件应默认关闭、仅在桌面精确指针开启（误触全屏回归锁）");
        assertFalse(html.contains("previewVideo\" muted autoplay playsinline webkit-playsinline x5-playsinline x5-video-player-type=\"h5\" controls"),
                "预览 video 不应保留原生 controls 属性（触屏误触全屏回归锁）");
        assertTrue(css.contains("#previewVideo::-webkit-media-controls"),
                "触屏端应有 webkit 媒体控件 CSS 兜底隐藏");
        // v2.3.1 真机验证第十一轮续：对讲音质修复 + 全屏悬浮控制
        assertTrue(js.contains("var wantRate = 8000"), "对讲应按机型目标采样率建上下文（PCMA 8k / OPUS 48k，浏览器不支持时兜底重采样）");
        assertTrue(js.contains("pool.shift() + pool[0]) * 0.5"), "16k→8k 应相邻平均（Nyquist 零点抗混叠）而非纯线性插值");
        assertTrue(js.contains("setInterval"), "对讲应有 40ms 匀速发送节奏器");
        // v2.3.1 第十六轮：8kHz 上下文主路径（对齐 go2rtc/lalmax）+ OPUS 机型 WebCodecs
        assertTrue(js.contains("var wantRate = 8000"), "对讲应首选 8kHz 采集上下文（设备扬声器原生采样率）");
        assertTrue(js.contains("talk.opus = msg.codec === 1032") && js.contains("new window.AudioEncoder"),
                "OPUS 机型（72ac1）应有 WebCodecs 编码路径");
        assertTrue(js.contains("wantRate = 48000"), "OPUS 机型（72ac1）应请求 48kHz 采集上下文");
        assertTrue(html.contains("fsOverlay") && html.contains("fsPtz") && html.contains("btnTalkFs"),
            "全屏浮层应含云台方向键与喊话按钮");
        assertTrue(js.contains("togglePreviewFullscreen") && js.contains("fs-fake"),
            "应支持标准全屏与 iOS 伪全屏双通道");
        assertTrue(js.contains("(v === 'light' || v === 'dark' || v === 'auto') ? v : 'auto'"), "主题默认应跟随系统");
        assertTrue(html.contains("value=\"auto\" checked"), "外观单选默认应为「跟随系统」");
        // v2.3.1 真机验证第三轮：固定编号排序 + 预览不再全屏 + A-law 修复
        assertTrue(js.contains("saveDeviceOrder"), "缺少摄像头顺序保存逻辑");
        assertTrue(js.contains("api/settings/device-order"), "缺少摄像头顺序端点调用");
        assertTrue(js.contains("q-move"), "设置页缺少摄像头调序按钮标记");
        assertFalse(html.contains("x5-video-player-fullscreen"), "预览不应保留 X5 强制全屏属性");
        assertTrue(js.contains("linearToAlaw"), "缺少标准 G.711 A-law 编码实现");
        assertTrue(css.contains(".q-no"), "缺少摄像头编号样式");
        // v2.3.1 回放自定义控制 + 事件页更多操作收纳 + 设置宽卡
        assertTrue(html.contains("btnPbPlay"), "回放缺少自定义播放按钮");
        assertTrue(html.contains("btnPbMute"), "回放缺少自定义静音按钮");
        assertTrue(html.contains("ev-more-actions"), "事件页缺少「更多操作」收纳");
        assertTrue(html.contains("set-card-wide"), "设置页缺少宽卡标记");
        assertTrue(js.contains("togglePbPlay"), "回放缺少自定义播放控制逻辑");
        // v2.3.1 真机验证第六轮：回放末帧等待交接 + 预载前移 + 录制段提示一次性 + 折叠组零渲染
        assertTrue(js.contains("waitThenSwap"), "回放缺少下一段未就绪时的末帧等待交接");
        assertTrue(js.contains("10 * 1000 * (v.playbackRate || 1)") || js.contains("10000 * (v.playbackRate"), "预载起点应按倍速前移");
        assertTrue(js.contains("recToastShown"), "录制段提示应每次会话只弹一次");
        assertTrue(js.contains("recGroupCollapsed"), "录像列表应折叠组零渲染（行只在展开时进 DOM）");
        assertTrue(css.contains("min-height: 36px"), "按钮应有统一最小高度（备份导入/导出等高）");
        // v2.3.1 入口收敛第二轮：卡片去掉「回放」按钮；录像列表 15s 缓存秒开
        assertTrue(!html.contains("pbPaneFiles"), "回放页不应再保留录像文件二级窗格切换");
        assertTrue(js.contains("recCache"), "录像文件弹层应有 15s 客户端缓存（秒开）");
        assertTrue(!js.contains("data-act=\"pb\""), "摄像头卡片不应再有回放按钮（入口收敛）");
        // v2.3.1 解锁秒开：缓存优先 + 新鲜度按需刷新 + 锁定停轮询
        assertTrue(js.contains("lastLoads"), "解锁后应按数据新鲜度决定是否重拉（设备 60s/概览 30s）");
        assertTrue(js.contains("setMiotProp"), "米家设备控制应有属性写入逻辑");
        assertTrue(html.contains("id=\"miotRows\"") && js.contains("renderMiotProps(did)"),
                "米家设备控制应按读取结果整表 JS 渲染（排版重构）");
        assertTrue(js.contains("miot-unsup-tag") && js.contains("class=\"miot-row"),
                "不支持的米家属性应灰显并打「不支持」标签");
        assertTrue(html.contains("data-miotset") || js.contains("data-miotset"), "米家控制行应有设置按钮标记");
        assertTrue(js.contains("toggleSleep") && !js.contains("wakeCamera"), "唤醒/休眠应统一为 toggleSleep");
        assertTrue(js.contains("stopAppTimers"), "锁定后应停止主界面轮询（消灭 401 空转）");
        // v2.3.1 回放续播新段 + 起始点落前一段
        assertTrue(js.contains("refreshTimelineLive().then"), "播到尾端应先刷新时间轴再定论（新封段无缝续播）");
        assertTrue(js.contains("cur.recording && cur.durMs > oldDur"), "录制段时长增长时应从断点续播");
        assertTrue(js.contains("prev.durMs / 1000 - 30"), "进回放起始点应落在上一段末尾 30 秒处");
        // v2.3.1 真机验证第七轮：每次进回放页都自动播放（不只首次），再次进入恢复播放
        assertTrue(js.contains("v.ended) { loadTimeline(true)"), "上次已播到末尾时应重新定位到最新段");
        // v2.3.1 第二十一轮：续播改为重试+可见提示（不再静默吞拒）
        assertTrue(js.contains("回放未自动续播，点一下画面即可继续"), "再次进入回放页应恢复播放且被拦时有提示");
        // v2.3.1 帮助与反馈：诊断导出 + 双平台 Issue 上报（无令牌降级）
        assertTrue(html.contains("data-sgroup=\"feedback\""), "设置页缺少「帮助与反馈」分组");
        assertTrue(js.contains("noteErr") && js.contains("unhandledrejection"), "缺少前端错误环形记录");
        assertTrue(js.contains("api/diagnostics/issue"), "缺少 Issue 上报调用");
        assertTrue(js.contains("api/diagnostics/export"), "缺少诊断报告导出调用");
        assertTrue(js.contains("feedbackFallback"), "缺少无令牌降级路径（复制报告+打开 Issue 页）");
        assertTrue(html.contains("btnFbGitee") && html.contains("btnFbGithub"), "缺少双平台上报按钮");
        assertTrue(html.contains("setFbGiteeToken") && html.contains("setFbGithubToken"), "缺少上报令牌输入框");
        // v2.3.1 真机验证第八轮：休眠闸门 NPE 热修复 + 更新检测 + 反馈卡美化
        assertTrue(js.contains("async function exportDiagnostics"), "诊断导出应使用 blob 下载（带鉴权头）");
        assertTrue(html.contains("btnFbCheckUpdate"), "缺少检查更新按钮");
        assertTrue(js.contains("api/diagnostics/update-check"), "缺少版本更新检测调用");
        // v2.3.1 入口收敛（docs/design/05 P1+P2）：录像文件唯一入口=卡片弹层，事件卡片入口强化
        assertTrue(html.contains("filesModal"), "录像文件应为弹层（从摄像头卡片进入）");
        assertTrue(html.contains("btnFilesAll"), "设置页应保留全部录像文件管理入口");
        assertTrue(html.contains("recLockChip"), "文件弹层应显示「仅看」锁定胶囊");
        assertTrue(html.contains("evLockChip"), "事件页应有「仅看」锁定胶囊");
        assertTrue(js.contains("openFilesModal"), "缺少文件弹层打开逻辑");
        assertTrue(!js.contains("gotoCamPlayback"), "卡片回放按钮已移除（入口收敛），不应再保留回放直达函数");
        assertTrue(js.contains("updateEvLockChip"), "缺少事件锁定胶囊同步逻辑");
        assertTrue(css.contains(".files-locked"), "锁定态应隐藏摄像头筛选（files-locked）");
        assertTrue(css.contains(".lock-chip"), "缺少锁定胶囊样式");
        assertTrue(css.contains(".cam-actions-sub"), "摄像头卡片应为双行操作区");
        assertFalse(html.contains("pbPaneFiles"), "回放页不应再保留「录像文件」二级窗格");
        assertFalse(js.contains("setPbPane"), "回放页二级窗格切换逻辑应已移除");
        // v2.3.1 飞牛桌面 SDK 融合 + 快照归档
        assertTrue(html.contains("vendor/trimapp-bridge.js"), "缺少 @trimjs/web-app 桥接脚本");
        assertTrue(html.contains("dirSysPick"), "目录弹窗缺少系统文件选择器按钮");
        assertTrue(html.contains("setSnapArchEnabled"), "缺少快照归档开关");
        assertTrue(html.contains("setArchiveDirInput"), "缺少归档目录输入");
        assertTrue(html.contains("data-sgroup=\"ha\""), "缺少智能联动（Home Assistant）设置分组");
        assertTrue(html.contains("btnSaveMqtt"), "缺少 MQTT 保存按钮");
        assertTrue(html.contains("value=\"auto\""), "主题缺少「跟随系统」选项");
        assertTrue(js.contains("whenTrimHost"), "缺少 SDK 宿主探测");
        assertTrue(js.contains("trimPickDir"), "缺少系统文件选择器调用");
        assertTrue(js.contains("trimOpenFile"), "缺少在文件管理中打开调用");
        assertTrue(js.contains("trimApplyTitle"), "缺少桌面标题跟随");
        assertTrue(js.contains("evOpenArchive"), "缺少事件归档打开逻辑");
        assertTrue(js.contains("api/settings/snapshot-archive"), "缺少快照归档设置端点调用");
        assertTrue(js.contains("sdk-host"), "缺少宿主探测后的 sdk-only 显隐切换");
        // v2.3.0 监控台壳层：侧栏 + 底部导航双入口，主题与快照设置存在
        assertTrue(html.contains("class=\"sidenav\""), "缺少侧栏导航");
        assertTrue(html.contains("class=\"bottomnav\""), "缺少底部导航");
        assertTrue(html.contains("data-sgroup=\"appearance\""), "缺少外观设置分组");
        assertTrue(html.contains("id=\"setCardSnap\""), "缺少摄像头卡快照开关");
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
