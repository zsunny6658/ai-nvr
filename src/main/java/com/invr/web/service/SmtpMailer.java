package com.invr.web.service;

import com.invr.store.NotificationSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 内置轻量 SMTP 客户端（v1.2.19 通知推送邮件通道）。
 *
 * <p>仅用 JDK 原生 {@link Socket} / {@link SSLSocket} 实现，不引入 jakarta.mail 等第三方
 * 依赖（离线构建环境拿不到）。覆盖日常可用面：</p>
 * <ul>
 *   <li>隐式 TLS（465）与 STARTTLS（587，或显式要求时任意端口）；</li>
 *   <li>AUTH LOGIN（用户非空时）；</li>
 *   <li>多收件人（逗号/分号/空白分隔）；</li>
 *   <li>UTF-8 主题用 RFC 2047 B 编码，正文 base64（Content-Transfer-Encoding: base64），
 *       避免编码协商差异导致的乱码。</li>
 * </ul>
 *
 * <p>协议容错只做"够用"级别：逐阶段校验 SMTP 应答码，任何阶段失败立即返回可读错误串
 * （含服务器原文首行），由调用方决定重试策略。发送失败不影响事件主流程。</p>
 */
public class SmtpMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    /** 建立到 SMTP 服务器的（可选 TLS）连接；测试注入假实现。 */
    public interface Connector {
        Socket connect(String host, int port, boolean implicitTls) throws IOException;
    }

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private final Connector connector;

    public SmtpMailer() {
        this((host, port, implicitTls) -> {
            if (implicitTls) {
                SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault()
                        .createSocket();
                s.setUseClientMode(true);
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(READ_TIMEOUT_MS);
                return s;
            }
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            return s;
        });
    }

    /** 测试用：注入假连接器。 */
    public SmtpMailer(Connector connector) {
        this.connector = connector;
    }

    /**
     * 发送纯文本邮件。成功返回 {@code null}，失败返回可读错误描述（不抛异常）。
     */
    public String send(NotificationSettings cfg, String subject, String body) {
        List<String> recipients = splitRecipients(cfg.mailTo());
        if (recipients.isEmpty()) {
            return "收件人为空";
        }
        String from = cfg.mailFrom().isBlank() ? cfg.smtpUser() : cfg.mailFrom();
        if (from.isBlank()) {
            return "发件人为空";
        }
        Socket socket = null;
        try {
            boolean implicitTls = cfg.smtpSsl() && cfg.smtpPort() == 465;
            socket = connector.connect(cfg.smtpHost(), cfg.smtpPort(), implicitTls);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();

            expect(in, 220, "连接");
            ehlo(in, out, socket.getLocalAddress().getHostName());

            // STARTTLS：非隐式 TLS 且用户要求加密时升级
            if (cfg.smtpSsl() && !implicitTls) {
                cmd(in, out, "STARTTLS", 220, "STARTTLS");
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(socket, cfg.smtpHost(), cfg.smtpPort(), true);
                tls.setUseClientMode(true);
                tls.setSoTimeout(READ_TIMEOUT_MS);
                socket = tls;
                in = new BufferedReader(
                        new InputStreamReader(tls.getInputStream(), StandardCharsets.ISO_8859_1));
                out = tls.getOutputStream();
                ehlo(in, out, tls.getLocalAddress().getHostName());
            }

            if (!cfg.smtpUser().isBlank()) {
                authLogin(in, out, cfg.smtpUser(), cfg.smtpPass());
            }

            cmd(in, out, "MAIL FROM:<" + angle(from) + ">", 250, "MAIL FROM");
            for (String rcpt : recipients) {
                cmd(in, out, "RCPT TO:<" + angle(rcpt) + ">", 25, "RCPT TO");
            }
            cmd(in, out, "DATA", 354, "DATA");

            StringBuilder data = new StringBuilder();
            data.append("From: ").append(from).append("\r\n");
            data.append("To: ").append(String.join(", ", recipients)).append("\r\n");
            data.append("Subject: =?UTF-8?B?")
                    .append(Base64.getEncoder().encodeToString(
                            subject.getBytes(StandardCharsets.UTF_8)))
                    .append("?=\r\n");
            data.append("Date: ").append(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                    .format(java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())))
                    .append("\r\n");
            data.append("MIME-Version: 1.0\r\n");
            data.append("Content-Type: text/plain; charset=UTF-8\r\n");
            data.append("Content-Transfer-Encoding: base64\r\n");
            data.append("\r\n");
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            String b64 = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.ISO_8859_1))
                    .encodeToString(raw);
            data.append(b64);
            // 点填充：行首的 "." 透明化（RFC 5321 4.5.2）；终止点随后单独发送不参与填充
            String payload = data.toString().replace("\r\n.", "\r\n..").replaceFirst("^\\.", "..");
            out.write(payload.getBytes(StandardCharsets.ISO_8859_1));
            out.write("\r\n.\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            expect(in, 250, "正文投递");

            try {
                cmd(in, out, "QUIT", 221, "QUIT");
            } catch (IOException ignore) {
                // 服务器先关连接不算失败：正文已确认 250
            }
            return null;
        } catch (IOException e) {
            log.warn("SMTP 发送失败 host={} port={}: {}", cfg.smtpHost(), cfg.smtpPort(), e.toString());
            return e.getMessage() == null ? e.toString() : e.getMessage();
        } finally {
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignore) {
                // 关闭失败不影响结果
            }
        }
    }

    // ===================== 协议细节 =====================

    private void ehlo(BufferedReader in, OutputStream out, String myName) throws IOException {
        cmd(in, out, "EHLO " + (myName == null || myName.isBlank() ? "invr" : myName), 250, "EHLO");
    }

    private void authLogin(BufferedReader in, OutputStream out, String user, String pass)
            throws IOException {
        cmd(in, out, "AUTH LOGIN", 334, "AUTH");
        cmd(in, out, Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8)),
                334, "AUTH 用户名");
        cmd(in, out, Base64.getEncoder().encodeToString(pass.getBytes(StandardCharsets.UTF_8)),
                235, "AUTH 密码");
    }

    private void cmd(BufferedReader in, OutputStream out, String line, int code, String stage)
            throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        expect(in, code, stage);
    }

    /** 读取（可能多行）应答并校验状态码；失败抛 IOException，消息带阶段与服务器原文。 */
    private void expect(BufferedReader in, int code, String stage) throws IOException {
        String first = in.readLine();
        if (first == null) {
            throw new IOException(stage + "：服务器无应答（连接被关闭）");
        }
        // 多行应答以 "250-..." 续行，"250 ..." 结束
        while (first.length() >= 4 && first.charAt(3) == '-') {
            String cont = in.readLine();
            if (cont == null) {
                throw new IOException(stage + "：应答中断");
            }
            first = cont;
        }
        int got;
        try {
            got = Integer.parseInt(first.substring(0, 3).trim());
        } catch (RuntimeException e) {
            throw new IOException(stage + "：应答格式异常：" + first);
        }
        if (!isAcceptable(code, got)) {
            throw new IOException(stage + "失败（" + got + "）：" + first);
        }
    }

    /** 阶段期望码与实际码的宽松匹配：25x 对 25，其余按首两/三位前缀匹配。 */
    private boolean isAcceptable(int expect, int got) {
        if (expect == 25) {
            return got >= 250 && got <= 259;
        }
        return got / 100 == expect / 100 || got / 10 == expect / 10;
    }

    /** 收件人串拆分：逗号 / 分号 / 空白均可。 */
    static List<String> splitRecipients(String mailTo) {
        List<String> out = new ArrayList<>();
        if (mailTo == null) {
            return out;
        }
        for (String p : mailTo.split("[,;\\s]+")) {
            if (!p.isBlank() && p.contains("@")) {
                out.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** 去掉收件人可能带的名字部分（"Name <a@b>" → "a@b"）。 */
    static String angle(String addr) {
        int lt = addr.lastIndexOf('<');
        int gt = addr.lastIndexOf('>');
        if (lt >= 0 && gt > lt) {
            return addr.substring(lt + 1, gt).trim();
        }
        return addr.trim();
    }
}
