package com.ainvr.web.service;

import com.ainvr.hub.CameraStream;
import com.ainvr.hub.StreamHub;
import com.ainvr.miss.MissCommands;
import com.ainvr.web.service.DeviceCatalog;
import com.ainvr.model.CameraDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对讲喊话 WebSocket（{@code /ws/talk/{did}}）。
 *
 * <p>协议：</p>
 * <ol>
 *   <li>浏览器建立连接（令牌以 {@code ?__ainvr_t=} 携带，握手时由门禁校验）；</li>
 *   <li>服务端在摄像头现有取流会话上发 SpeakerStart 0x106，按 go2rtc 惯例等待约 700ms
 *       让设备准备好扬声器通道，然后回文本 JSON：
 *       {@code {"ok":true,"codec":1027,"linearPcm":false,"sampleRate":8000}}；
 *       失败回 {@code {"ok":false,"error":"..."}} 并关闭；</li>
 *   <li>浏览器实时上行二进制音频块（A-law 320B/40ms 或线性 PCM 640B/40ms），
 *       服务端透传为 MISS 上行音频包（CS2 通道 3）；</li>
 *   <li>连接关闭或上游会话重建时服务端发 SpeakerStop 0x108。</li>
 * </ol>
 *
 * <p>同一台设备同一时刻只允许一路喊话（扬声器天然独占），重复连接会被拒绝。</p>
 */
@Component
public class TalkWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TalkWebSocketHandler.class);

    private static final Pattern DID_PATTERN = Pattern.compile("/ws/talk/([^/?]+)");
    /** 单个音频块上限：线性 PCM 640B（40ms@8k）的 2.5 倍，防误发大包。 */
    private static final int MAX_CHUNK_BYTES = 1600;
    /** SpeakerStart 后到首个音频包的间隔（对齐 go2rtc 固定 sleep 1s，留余量）。 */
    private static final long SPEAKER_WARMUP_MS = 700;

    private final StreamHub hub;
    private final DeviceCatalog catalog;

    /** 每台设备当前对讲会话：did → WS 会话 id。天然独占扬声器。 */
    private final Map<String, String> activeByDid = new ConcurrentHashMap<>();

    public TalkWebSocketHandler(StreamHub hub, DeviceCatalog catalog) {
        this.hub = hub;
        this.catalog = catalog;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String did = extractDid(session);
        if (did == null || did.isBlank()) {
            reject(session, "URL 中缺少设备 DID");
            return;
        }
        // did 可能带 #2 副镜头后缀：对讲按真实设备会话走（用主镜头流）
        String baseDid = com.ainvr.miss.DualLens.baseDid(did);
        CameraDevice device = catalog.findOrRefresh(baseDid);
        if (device == null) {
            reject(session, "设备列表中未找到 did=" + baseDid);
            return;
        }
        if (!MissCommands.speakerSupported(device.model())) {
            reject(session, "该机型（" + device.model() + "）对讲上行需要 OPUS，暂不支持");
            return;
        }
        if (activeByDid.containsKey(baseDid)) {
            reject(session, "该摄像头正在被另一路喊话占用（扬声器独占）");
            return;
        }
        if (!hub.isHealthy(baseDid)) {
            reject(session, "该摄像头当前没有可用的取流会话，请先打开实时预览再使用喊话");
            return;
        }
        CameraStream.SpeakerSession speaker = hub.speakerStart(baseDid);
        if (speaker == null) {
            reject(session, "对讲开启失败：取流会话不可用");
            return;
        }
        activeByDid.put(baseDid, session.getId());
        session.getAttributes().put("did", baseDid);
        session.getAttributes().put("speaker", speaker);
        // 对齐 go2rtc：SpeakerStart 后等待设备准备扬声器通道再接收音频
        Thread.sleep(SPEAKER_WARMUP_MS);
        String json = "{\"ok\":true,\"codec\":" + speaker.codec()
                + ",\"linearPcm\":" + speaker.linearPcm()
                + ",\"sampleRate\":8000"
                + ",\"deviceReplied\":" + (hub.speakerReply(baseDid).replyAt() > 0)
                + "}";
        session.sendMessage(new TextMessage(json));
        log.info("[{}] 对讲上行已开启（codec={}，linearPcm={}）", baseDid, speaker.codec(), speaker.linearPcm());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String did = (String) session.getAttributes().get("did");
        CameraStream.SpeakerSession speaker =
                (CameraStream.SpeakerSession) session.getAttributes().get("speaker");
        if (did == null || speaker == null) {
            return;
        }
        java.nio.ByteBuffer buf = message.getPayload();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        if (payload.length == 0 || payload.length > MAX_CHUNK_BYTES) {
            log.warn("[{}] 对讲音频块大小异常（{} 字节），忽略", did, payload.length);
            return;
        }
        try {
            hub.speakerWrite(did, speaker, payload);
        } catch (IOException e) {
            log.info("[{}] 对讲上行中断：{}", did, e.getMessage());
            closeQuietly(session, CloseStatus.GOING_AWAY.withReason("上游取流会话已重建"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 预留：{"op":"stop"} 由客户端主动结束（等价于直接断开）
        String body = message.getPayload();
        if (body != null && body.contains("\"stop\"")) {
            closeQuietly(session, CloseStatus.NORMAL);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String did = (String) session.getAttributes().get("did");
        if (did == null) {
            return;
        }
        CameraStream.SpeakerSession speaker =
                (CameraStream.SpeakerSession) session.getAttributes().get("speaker");
        activeByDid.remove(did, session.getId());
        hub.speakerStop(did, speaker);
        log.info("[{}] 对讲上行已结束（status={}）", did, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        closeQuietly(session, CloseStatus.SERVER_ERROR);
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
                    ("{\"ok\":false,\"error\":\"" + reason + "\"}")
                            .getBytes(StandardCharsets.UTF_8)));
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
