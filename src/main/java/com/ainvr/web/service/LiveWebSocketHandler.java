package com.ainvr.web.service;

import com.ainvr.hub.StreamHub;
import com.ainvr.miss.DualLens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 预览直播 WebSocket 推流（{@code /ws/live/{did}}）——v2.3.1 第二十六轮成熟方案。
 *
 * <p>背景：iPhone 局域网 HTTP WKWebView 里，{@code ManagedMediaSource}（iOS 17.1+ 的
 * 唯一 MediaSource API）<strong>不要求 secure context</strong>（W3C Media Source 规范无
 * {@code [SecureContext]} 标记），但必须用 {@code video.srcObject = ms} 挂接并设
 * {@code disableRemotePlayback=true} 才会激活。此前 live.mp4 用 {@code video.src=blob:}
 * 属 URL 资源加载路径 → AVPlayer 接管 → 系统全屏（go2rtc 源码注释、WebKit 博客、
 * openHAB #1157 均可佐证），这正是「预览自动进系统全屏」的根因。</p>
 *
 * <p>协议（go2rtc mse 模式同款）：</p>
 * <ol>
 *   <li>浏览器建连后服务端推送第一帧二进制 = {@code init.mp4 + 起播窗口分片}；</li>
 *   <li>随后每个二进制帧 = 新完成的 fMP4 分片（<strong>不含 init</strong>，
 *       同一 SourceBuffer 重复 append init 会直接 MediaError）；</li>
 *   <li>直播中断/设备离线时发文本 {@code {"live":false}} 并关闭；</li>
 *   <li>断开后服务端停止推流线程；设备无订阅时由 HLS 空闲回收机制退订上游。</li>
 * </ol>
 *
 * <p>同一设备允许多路并发观看（浏览器各自独立 append，互不影响）。</p>
 */
@Component
public class LiveWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketHandler.class);

    private static final Pattern DID_PATTERN = Pattern.compile("/ws/live/([^/?]+)");

    private final HlsService hls;
    private final StreamHub hub;

    /** did → 活跃推送会话数（诊断用）。 */
    private final Map<String, AtomicBoolean> activeByDid = new ConcurrentHashMap<>();

    public LiveWebSocketHandler(HlsService hls, StreamHub hub) {
        this.hls = hls;
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String did = extractDid(session);
        if (did == null || did.isBlank()) {
            reject(session, "URL 中缺少设备 DID");
            return;
        }
        String baseDid = DualLens.baseDid(did);
        session.getAttributes().put("did", baseDid);
        activeByDid.computeIfAbsent(baseDid, k -> new AtomicBoolean()).set(true);
        PushLoop loop = new PushLoop(session, baseDid);
        session.getAttributes().put("loop", loop);
        Thread t = new Thread(loop, "ws-live-" + baseDid);
        t.setDaemon(true);
        t.start();
        log.info("[{}] 预览 WS 推流已开启", baseDid);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String did = (String) session.getAttributes().get("did");
        PushLoop loop = (PushLoop) session.getAttributes().get("loop");
        if (loop != null) {
            loop.stop();
        }
        if (did != null) {
            AtomicBoolean flag = activeByDid.get(did);
            if (flag != null) {
                flag.set(false);
            }
            log.info("[{}] 预览 WS 推流已结束（status={}）", did, status);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        PushLoop loop = (PushLoop) session.getAttributes().get("loop");
        if (loop != null) {
            loop.stop();
        }
        closeQuietly(session, CloseStatus.SERVER_ERROR);
    }

    /**
     * 单连接推流循环：首包 init+窗口，此后只推新分片；设备离线或写失败即终止。
     * {@link HlsService#liveChunk} 的长轮询等待天然形成 6s 节奏，无需额外限速。
     */
    private class PushLoop implements Runnable {
        private final WebSocketSession session;
        private final String did;
        private final AtomicBoolean running = new AtomicBoolean(true);

        PushLoop(WebSocketSession session, String did) {
            this.session = session;
            this.did = did;
        }

        void stop() {
            running.set(false);
        }

        @Override
        public void run() {
            Integer from = null;
            try {
                while (running.get() && session.isOpen()) {
                    // 首包只带 init+1 片（第二十七轮真机实锤：等 2 片在 6s 分片节奏下
                    // 冷启动 ≈13s，超过 12s 截止被误判「直播中断」→ 降级 blob → 系统全屏，
                    // 且起播要多等一个分片周期）。1 片 ≈8s 即可起播，后续 2 片维持平滑。
                    HlsService.LiveChunk chunk = from == null
                            ? hls.liveChunk(did, null, 1, 18000)
                            : hls.liveSegments(did, from, 2, 6000);
                    if (chunk == null) {
                        // 设备离线/直播中断：通知前端走降级链路
                        if (session.isOpen()) {
                            try {
                                session.sendMessage(new TextMessage("{\"live\":false}"));
                            } catch (IOException ignored) {
                                // 连接已断
                            }
                        }
                        return;
                    }
                    from = chunk.to() + 1;
                    session.sendMessage(new BinaryMessage(chunk.data()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // 客户端断开：静默结束
            } finally {
                stop();
            }
        }
    }

    private String extractDid(WebSocketSession session) {
        java.net.URI uri = session.getUri();
        if (uri == null) {
            return null;
        }
        Matcher m = DID_PATTERN.matcher(uri.getPath());
        return m.find() ? m.group(1) : null;
    }

    private void reject(WebSocketSession session, String reason) {
        try {
            session.sendMessage(new TextMessage(
                    ("{\"live\":false,\"error\":\"" + reason + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            // 连接可能已关闭
        }
        closeQuietly(session, CloseStatus.POLICY_VIOLATION.withReason(reason));
    }

    private static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {
            // 忽略关闭异常
        }
    }
}
