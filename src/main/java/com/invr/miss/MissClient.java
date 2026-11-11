package com.invr.miss;

import com.invr.crypto.ChaCha20Cipher;
import com.invr.crypto.X25519Keys;
import com.invr.model.P2pConnectParams;
import com.invr.p2p.cs2.Cs2Connection;
import com.invr.p2p.cs2.Cs2Dialer;
import com.invr.util.CryptoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * MISS 会话：CS2 连接之上的登录握手、加密命令与媒体包读取。
 *
 * <p>1:1 对照 go2rtc pkg/xiaomi/miss/client.go。
 */
public final class MissClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MissClient.class);

    private final Cs2Connection conn;
    private final byte[] sharedKey;
    private final String model;
    private volatile boolean closed;

    /** 最近一次云台命令的设备应答（0x113 明文），由命令通道排空线程写入。 */
    private volatile String lastMotorReply;
    private volatile long lastMotorReplyAt;
    /** 最近一次云台命令的发出时刻，用于判断 0x113 是否是本次命令的应答。 */
    private volatile long lastMotorCommandAt;

    private MissClient(Cs2Connection conn, byte[] sharedKey, String model) {
        this.conn = conn;
        this.sharedKey = sharedKey;
        this.model = model;
    }

    /**
     * 建立 CS2 连接并完成 MISS 登录。
     *
     * @param params    miss_get_vendor 协商结果
     * @param transport ""=设备决定、"udp"、"tcp"
     */
    public static MissClient connect(P2pConnectParams params, String transport) throws IOException {
        Cs2Connection conn = Cs2Dialer.dial(params.host(), transport);

        byte[] clientPrivate = CryptoUtils.unhex(params.clientPrivateHex());
        byte[] devicePublic = CryptoUtils.unhex(params.devicePublicHex());
        byte[] sharedKey = X25519Keys.calcSharedKey(clientPrivate, devicePublic);

        try {
            login(conn, params.clientPublicHex(), params.sign());
        } catch (IOException e) {
            conn.close();
            throw e;
        }
        log.info("MISS 会话登录成功（model={}）", params.model());
        MissClient client = new MissClient(conn, sharedKey, params.model());
        client.startCommandDrain();
        return client;
    }

    /**
     * 持续消费 CS2 命令通道（ch0）。
     *
     * <p>设备在取流期间会周期性（实测约每 10 秒）回加密命令（流控/保活/状态）。
     * 本项目原先只在 login 时读一次 AuthRes，之后 ch0 再无人消费；ch0 队列容量仅 10，
     * 约 100 秒后必然溢出，worker 抛 "pop buffer is full" 并把整条连接判为断流 ——
     * 这正是正常出画面的摄像头也会每约 2 分钟无条件重连一次的根因。</p>
     *
     * <p>这里用独立后台线程持续排空 ch0，同时把设备返回的命令字解密后写入日志，
     * 便于直接看到「设备明确拒绝推流」这类以往被静默丢弃的响应。</p>
     */
    private void startCommandDrain() {
        Thread drain = new Thread(() -> {
            int seen = 0;
            while (!closed && !Thread.currentThread().isInterrupted()) {
                try {
                    byte[] raw = conn.readCommandRaw();
                    if (raw == null || raw.length < 4) {
                        continue;
                    }
                    int cmd = be32(raw, 0);
                    String detail = "";
                    if (cmd == MissCommands.CMD_ENCODED && raw.length > 4) {
                        try {
                            byte[] plain = ChaCha20Cipher.decode(sharedKey,
                                    Arrays.copyOfRange(raw, 4, raw.length));
                            int inner = plain.length >= 4 ? be32(plain, 0) : -1;
                            detail = String.format(" -> 内部命令 0x%x（%d 字节）",
                                    inner, Math.max(0, plain.length - 4));
                            if (inner == MissCommands.CMD_MOTOR_RES) {
                                // 云台命令的设备应答：以前只记录长度，界面无从判断「发出去了但设备不理」。
                                // 这里把明文内容记下来并交给请求线程回传前端。
                                String reply = readableUtf8(plain, 4);
                                lastMotorReply = reply;
                                lastMotorReplyAt = System.currentTimeMillis();
                                detail += " 云台应答：" + reply;
                                log.info("MISS 云台设备应答 0x113：{}", reply);
                            } else if (inner != 0x114 && plain.length > 4) {
                                detail += " 明文：" + readableUtf8(plain, 4);
                            }
                        } catch (Exception de) {
                            detail = " -> 解密失败：" + de;
                        }
                    }
                    seen++;
                    if (seen <= 3 || seen % 60 == 0) {
                        log.info("MISS 设备命令 ch0=0x{}{}（第 {} 条）",
                                Integer.toHexString(cmd), detail, seen);
                    } else {
                        log.debug("MISS 设备命令 ch0=0x{}{}", Integer.toHexString(cmd), detail);
                    }
                } catch (Exception e) {
                    if (!closed) {
                        log.debug("MISS 命令通道消费线程退出：{}", e.toString());
                    }
                    break;
                }
            }
        }, "miss-cmd-drain-" + model);
        drain.setDaemon(true);
        drain.start();
    }

    public String protocol() {
        return conn.protocol() + " / " + model;
    }

    /**
     * AuthReq 0x100 明文 JSON，等待 AuthRes 0x101 且 result=success。
     */
    private static void login(Cs2Connection conn, String clientPublicHex, String sign) throws IOException {
        String authJson = "{\"public_key\":\"" + clientPublicHex + "\",\"sign\":\"" + sign
                + "\",\"uuid\":\"\",\"support_encrypt\":0}";
        log.info("发送 MISS AuthReq(0x100)，等待设备鉴权响应...");
        conn.writeCommand(MissCommands.CMD_AUTH_REQ, authJson.getBytes(StandardCharsets.UTF_8));

        byte[] response = conn.readCommandRaw(com.invr.p2p.cs2.Cs2.LOGIN_RESPONSE_TIMEOUT_MS);
        byte[] body = Arrays.copyOfRange(response, 4, response.length);
        String text = new String(body, StandardCharsets.UTF_8);
        if (!text.contains("\"result\":\"success\"")) {
            throw new IOException("miss: 登录握手失败：" + text);
        }
    }

    /**
     * 加密命令：BE32(cmd)+body 经 ChaCha20（自带 8B nonce）后包在 0x1001 内。
     */
    private void writeEncrypted(byte[] outer) throws IOException {
        byte[] encrypted = ChaCha20Cipher.encode(sharedKey, outer);
        conn.writeCommand(MissCommands.CMD_ENCODED, encrypted);
    }

    /**
     * 开始取流。
     *
     * @param channel 镜头通道：""或"0"=主镜头；"2"=第二镜头
     * @param quality hd(默认)/sd/auto
     * @param audio   "0"关、"1"开；null 默认开
     */
    public void startMedia(String channel, String quality, String audio) throws IOException {
        String q;
        if (quality == null || quality.isEmpty() || "hd".equalsIgnoreCase(quality)) {
            q = MissCommands.defaultQuality(model);
        } else if ("sd".equalsIgnoreCase(quality)) {
            q = "1";
        } else if ("auto".equalsIgnoreCase(quality)) {
            q = "0";
        } else {
            q = quality;
        }
        String a = (audio == null || audio.isEmpty()) ? "1" : audio;

        String json;
        if (channel == null || channel.isEmpty() || "0".equals(channel)) {
            json = "{\"videoquality\":" + q + ",\"enableaudio\":" + a + "}";
        } else {
            json = "{\"videoquality\":-1,\"videoquality2\":" + q + ",\"enableaudio\":" + a + "}";
        }
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] outer = new byte[4 + jsonBytes.length];
        putBe32(outer, MissCommands.CMD_VIDEO_START);
        System.arraycopy(jsonBytes, 0, outer, 4, jsonBytes.length);
        writeEncrypted(outer);
    }

    /**
     * 停止取流（VideoStop 0x103，无 body）。
     */
    public void stopMedia() throws IOException {
        byte[] outer = new byte[4];
        putBe32(outer, MissCommands.CMD_VIDEO_STOP);
        writeEncrypted(outer);
    }

    /**
     * 云台控制（MISS CmdMotorReq 0x112），复用当前取流会话，不新建连接、不占用额外通道。
     *
     * <p>命令与 VideoStart 一样封装为 {@code BE32(cmd) + JSON}，ChaCha20 加密后以 0x1001 发送。
     * 两种载荷格式来自不同的实测实现：</p>
     * <ul>
     *   <li>{@code direction}（默认）：{@code {"direction":"left","speed":5}}，
     *       与 go2rtc / lalmax-nvr 现行实现一致，支持 stop；</li>
     *   <li>{@code operation}：{@code {"operation":1}}，1 左 / 2 右 / 3 上 / 4 下 / 0 停，
     *       部分旧固件用这种步进式命令。</li>
     * </ul>
     *
     * <p>注意：协议本身不提供「设备是否带云台电机」的可靠查询，部分机型会静默忽略该命令。
     * 设备若有应答会由命令通道排空线程写入日志，可据此现场确认兼容性。</p>
     *
     * <p>v1.2.1：命令发出后立即返回，不再阻塞等待 0x113。此前每次点击都要等满 800ms
     * 让前端拿到「设备是否应答」，即使设备已经转动，界面也要晚近 1 秒才有反应；而不回
     * 0x113 的机型每次都要白等满 800ms。应答改由 {@link #motorReplyAt()} /
     * {@link #motorCommandAt()} 异步查询，云端控制接口得以毫秒级返回。</p>
     */
    public String motor(String direction, int speed, String mode) throws IOException {
        String d = direction == null ? "stop" : direction.toLowerCase();
        int op;
        switch (d) {
            case "left" -> op = 1;
            case "right" -> op = 2;
            case "up" -> op = 3;
            case "down" -> op = 4;
            case "stop" -> op = 0;
            default -> throw new IOException("不支持的云台方向：" + direction);
        }
        int s = Math.max(0, Math.min(100, speed));
        String json = "operation".equalsIgnoreCase(mode)
                ? "{\"operation\":" + op + "}"
                : "{\"direction\":\"" + d + "\",\"speed\":" + s + "}";
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        byte[] outer = new byte[4 + jsonBytes.length];
        putBe32(outer, MissCommands.CMD_MOTOR_REQ);
        System.arraycopy(jsonBytes, 0, outer, 4, jsonBytes.length);
        // 只打时间戳、发命令，立刻返回。0x113 应答由命令通道排空线程异步写入，
        // 前端可用 capabilities 接口读到「本次命令之后是否收到应答」。
        lastMotorCommandAt = System.currentTimeMillis();
        writeEncrypted(outer);
        log.info("MISS 云台命令 0x112 已发送：{}（mode={}）", json,
                "operation".equalsIgnoreCase(mode) ? "operation" : "direction");
        return null;
    }

    /** 最近一次 0x113 云台应答明文（可能为 null：设备不回应该命令）。 */
    public String motorReply() {
        return lastMotorReply;
    }

    /** 最近一次 0x113 应答到达时刻（0 表示从未收到）。 */
    public long motorReplyAt() {
        return lastMotorReplyAt;
    }

    /** 最近一次 0x112 命令发出时刻（0 表示本会话尚未发过云台命令）。 */
    public long motorCommandAt() {
        return lastMotorCommandAt;
    }

    /** 写入大端 32 位命令字。 */
    private static void putBe32(byte[] dst, int cmd) {
        dst[0] = (byte) ((cmd >>> 24) & 0xFF);
        dst[1] = (byte) ((cmd >>> 16) & 0xFF);
        dst[2] = (byte) ((cmd >>> 8) & 0xFF);
        dst[3] = (byte) (cmd & 0xFF);
    }

    /**
     * 读取并解密一个媒体包。
     */
    public MissPacket readPacket() throws IOException {
        byte[] message = conn.readMediaMessage();
        byte[] header = Arrays.copyOfRange(message, 0, 32);
        byte[] encryptedPayload = Arrays.copyOfRange(message, 32, message.length);

        byte[] payload = ChaCha20Cipher.decode(sharedKey, encryptedPayload);

        int codecId = le32(header, 4);
        int sequence = le32(header, 8);
        int flags = le32(header, 12);
        long timestampMs;
        if (MissCommands.usesLocalTimestamp(model)) {
            timestampMs = System.currentTimeMillis();
        } else {
            // 设备毫秒时钟只在低 32 位（header[20:24] 恒为 0xFFFFFFFF 的无关字段），按无符号读取，
            // 与 go2rtc 等价（它读 uint64 后转 RTP uint32，实际只用低 32 位、约 49 天回绕）。
            timestampMs = Integer.toUnsignedLong(le32(header, 16));
        }
        if (DUMP_PKT) {
            boolean video = codecId == MissCommands.CODEC_H264 || codecId == MissCommands.CODEC_H265;
            int n = video ? dumpVideo++ : dumpAudio++;
            if (n < 14) {
                System.err.printf("[PKT] %s codec=%d seq=%d flags=0x%08x ts=%d hdr=%s%n",
                        video ? "VIDEO" : "AUDIO", codecId, sequence, flags, timestampMs,
                        org.bouncycastle.util.encoders.Hex.toHexString(header));
            }
        }
        return new MissPacket(codecId, sequence, flags, timestampMs, payload);
    }

    /** 诊断开关：-Dinvr.dumppkt=true 时打印前若干媒体包头。 */
    private static final boolean DUMP_PKT = Boolean.getBoolean("invr.dumppkt");
    private int dumpVideo;
    private int dumpAudio;

    @Override
    public void close() throws IOException {
        // 幂等：wakeup() 与取流线程的 finally 可能同时关闭同一个会话，
        // 重复发 VideoStop / 重复关 socket 会让设备侧收到多余的停止命令。
        if (closed) {
            return;
        }
        closed = true;
        // 对齐 go2rtc Producer.Stop()：先显式通知设备停止取流，释放设备侧会话。
        // 只关 socket 而不发 VideoStop 时，设备侧会话需等自身超时才回收；
        // 叠加无限重连会迅速占满摄像头仅有的几路并发通道（小米机型尤甚）。
        try {
            stopMedia();
        } catch (Exception e) {
            log.debug("关闭前发送 VideoStop 失败（忽略）：{}", e.toString());
        }
        conn.close();
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** 把设备应答体按 UTF-8 转成可写进日志/界面的文本，过滤控制字符并限长。 */
    private static String readableUtf8(byte[] b, int off) {
        if (b == null || off >= b.length) {
            return "";
        }
        String text = new String(b, off, Math.min(b.length - off, 400), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            sb.append(c >= 0x20 || c == '\n' || c == '\t' ? c : ' ');
        }
        return sb.toString().trim();
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static long le64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= (long) (b[off + i] & 0xFF) << (8 * i);
        }
        return v;
    }
}
