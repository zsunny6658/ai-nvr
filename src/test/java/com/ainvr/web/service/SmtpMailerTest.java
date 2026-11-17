package com.ainvr.web.service;

import com.ainvr.store.NotificationSettings;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内置 SMTP 客户端协议流测试（v1.2.19）：脚本化假服务器逐阶段校验。
 */
class SmtpMailerTest {

    /** 假 SMTP 服务器：按脚本应答，收集收到的命令与 DATA 正文。 */
    private static class FakeSmtp implements Runnable {
        final ServerSocket server;
        final List<String> commands = new ArrayList<>();
        String dataBody = "";
        final CountDownLatch done = new CountDownLatch(1);
        String authFailure = null; // 非空则 AUTH 密码阶段以此应答并断开
        // AUTH LOGIN 状态：0 未开始 / 1 等用户名 / 2 等密码
        int authStage = 0;

        FakeSmtp() throws Exception {
            server = new ServerSocket(0);
            server.setSoTimeout(10_000);
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void run() {
            try (Socket s = server.accept()) {
                s.setSoTimeout(10_000);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                PrintStream out = new PrintStream(s.getOutputStream(), true, StandardCharsets.ISO_8859_1);
                out.println("220 fake ESMTP ready");
                String line;
                boolean inData = false;
                StringBuilder data = new StringBuilder();
                while ((line = in.readLine()) != null) {
                    if (inData) {
                        if (".".equals(line)) {
                            inData = false;
                            dataBody = data.toString();
                            commands.add("DATA");
                            out.println("250 OK queued");
                            continue;
                        }
                        data.append(line).append('\n');
                        continue;
                    }
                    commands.add(line);
                    String cmd = line.toUpperCase();
                    if (cmd.startsWith("EHLO")) {
                        out.println("250-fake greets you");
                        out.println("250 AUTH LOGIN PLAIN");
                    } else if (cmd.equals("AUTH LOGIN")) {
                        authStage = 1;
                        out.println("334 " + Base64.getEncoder().encodeToString("Username:".getBytes()));
                    } else if (authStage == 1) {
                        authStage = 2;
                        out.println("334 " + Base64.getEncoder().encodeToString("Password:".getBytes()));
                    } else if (authStage == 2) {
                        authStage = 0;
                        if (authFailure != null) {
                            out.println(authFailure);
                            return;
                        }
                        out.println("235 2.7.0 authentication succeeded");
                    } else if (cmd.startsWith("MAIL FROM:") || cmd.startsWith("RCPT TO:")) {
                        out.println("250 OK");
                    } else if (cmd.equals("DATA")) {
                        out.println("354 go ahead");
                        inData = true;
                    } else if (cmd.equals("QUIT")) {
                        out.println("221 bye");
                        break;
                    } else {
                        out.println("250 OK");
                    }
                }
            } catch (Exception e) {
                // 客户端断开属正常
            } finally {
                done.countDown();
            }
        }
    }

    private NotificationSettings cfg(int port, String to) {
        return new NotificationSettings(false, List.of(), "", "",
                false, "", port, false, "user", "pass", "", to);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void sendsMailWithAuthAndMultipleRecipients() throws Exception {
        FakeSmtp fake = new FakeSmtp();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(fake);
        SmtpMailer mailer = new SmtpMailer((host, port, tls) -> new Socket("127.0.0.1", port));
        String err = mailer.send(cfg(fake.port(), "a@example.com, b@example.com"),
                "测试主题：变动告警", "正文第一行\n正文第二行");
        assertNull(err, "发送应成功，失败时：" + err);
        assertTrue(fake.done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        List<String> cmds = fake.commands;
        assertTrue(cmds.stream().anyMatch(c -> c.startsWith("EHLO")), "缺少 EHLO");
        assertTrue(cmds.contains("AUTH LOGIN"));
        assertTrue(cmds.contains(b64("user")), "AUTH 用户名载荷");
        assertTrue(cmds.contains(b64("pass")), "AUTH 密码载荷");
        assertTrue(cmds.contains("MAIL FROM:<user>"), "发件人默认取 SMTP 账号：" + cmds);
        assertTrue(cmds.contains("RCPT TO:<a@example.com>"));
        assertTrue(cmds.contains("RCPT TO:<b@example.com>"));
        assertTrue(cmds.contains("QUIT"));

        String body = fake.dataBody;
        assertTrue(body.contains("Subject: =?UTF-8?B?" + b64("测试主题：变动告警") + "?="),
                "主题应为 RFC 2047 B 编码：" + body);
        assertTrue(body.contains("Content-Transfer-Encoding: base64"));
        String decoded = new String(java.util.Base64.getMimeDecoder().decode(
                body.substring(body.indexOf("\n\n") + 2).replaceAll("\r?\n", "")),
                StandardCharsets.UTF_8);
        assertTrue(decoded.contains("正文第一行"), "解码正文应含原文：" + decoded);
    }

    @Test
    void authFailureReturnsReadableError() throws Exception {
        FakeSmtp fake = new FakeSmtp();
        fake.authFailure = "535 5.7.8 bad credentials";
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(fake);
        SmtpMailer mailer = new SmtpMailer((host, port, tls) -> new Socket("127.0.0.1", port));
        String err = mailer.send(cfg(fake.port(), "a@example.com"), "主题", "正文");
        assertTrue(err != null && err.contains("535"), "应返回带 535 的错误：" + err);
        assertTrue(err.contains("AUTH"), "错误应标注失败阶段：" + err);
        assertTrue(fake.done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();
    }

    @Test
    void emptyRecipientsRejected() {
        SmtpMailer mailer = new SmtpMailer();
        assertEquals("收件人为空",
                mailer.send(cfg(1, "not-an-email"), "t", "b"));
    }

    @Test
    void recipientWithNamePartIsStripped() {
        assertEquals("a@b.com", SmtpMailer.angle("\"张三\" <a@b.com>"));
        assertEquals("a@b.com", SmtpMailer.angle("a@b.com"));
    }
}
