package com.ainvr.web.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.3.1 诊断报告脱敏与上报令牌存取。
 *
 * <p>隐私红线：诊断报告与 Issue 正文永远不能包含设备 DID 原文、局域网完整 IP、
 * MAC 地址或任何令牌类字段值——用户家庭部署形态属于敏感信息。</p>
 */
class DiagnosticsServiceTest {

    @Test
    void sanitizeMasksDidIpMacAndSecrets() {
        String in = "did=1149849923 ip=192.168.31.148 mac=AA:BB:CC:DD:EE:FF "
                + "token=ghp_abcdefghijklmnop1234 password=\"s3cretValue\" sid=12345";
        String out = DiagnosticsService.sanitize(in);
        assertFalse(out.contains("1149849923"), "DID 原文必须被打码");
        assertTrue(out.contains("114****9923"), "DID 应保留前 3 后 4");
        assertFalse(out.contains("192.168.31.148"), "完整 IP 必须被打码");
        assertTrue(out.contains("192.168.*.*"), "IP 应保留前两段");
        assertFalse(out.contains("AA:BB:CC:DD:EE:FF"), "完整 MAC 必须被打码");
        assertFalse(out.contains("DD:EE:FF"), "MAC 后半段不应保留");
        assertFalse(out.contains("DD:EE:FF"), "MAC 后半段不应保留");
        assertTrue(out.contains("AA:BB:CC:**:**"), "MAC 应保留前 3 字节");
        assertFalse(out.contains("ghp_abcdefghijklmnop1234"), "令牌必须被打码");
        assertFalse(out.contains("s3cretValue"), "password 字段值必须被打码");
        // 短数字（端口/分段秒数等）不受影响
        assertTrue(out.contains("sid=12345"), "短数字不应被打码");
    }

    @Test
    void maskDidKeepsShortValues() {
        assertEquals("114****9923", DiagnosticsService.maskDid("1149849923"));
        assertEquals("ab", DiagnosticsService.maskDid("ab"));
        assertEquals("?", DiagnosticsService.maskDid(null));
    }

    @Test
    void reportTokenSaveClearAndReject() throws Exception {
        Path dir = Files.createTempDirectory("ainvr-diag-test");
        try {
            com.ainvr.store.AppSettingsStore store = new com.ainvr.store.AppSettingsStore(dir);
            assertNull(store.reportToken("gitee"));
            assertNull(store.reportToken("github"));
            store.saveReportToken("gitee", " tok123 ");
            assertEquals("tok123", store.reportToken("gitee"));
            assertNull(store.reportToken("github"), "互不影响");
            // 重新实例化验证落盘
            assertEquals("tok123", new com.ainvr.store.AppSettingsStore(dir).reportToken("gitee"));
            store.saveReportToken("gitee", "");
            assertNull(store.reportToken("gitee"), "空串应清除");
            try {
                store.saveReportToken("wechat", "x");
                assertFalse(true, "非法 platform 应抛出");
            } catch (IllegalArgumentException expected) {
                // 预期
            }
        } finally {
            Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        }
    }

    @Test
    void versionCompare() {
        // 更新检测的版本号数值化比较：逐段比、短段补 0、忽略非数字后缀
        assertTrue(DiagnosticsService.isNewer("v2.4.0", "v2.3.1"));
        assertTrue(DiagnosticsService.isNewer("2.3.2", "2.3.1"));
        assertTrue(DiagnosticsService.isNewer("v2.4.10", "v2.4.9"), "数值比较不是字典序");
        assertFalse(DiagnosticsService.isNewer("v2.3.1", "v2.3.1"));
        assertFalse(DiagnosticsService.isNewer("v2.3.0", "v2.3.1"));
        assertFalse(DiagnosticsService.isNewer(null, "v2.3.1"));
    }

    @Test
    void reportContainsMaskedOverview() {
        // buildReport 依赖 DeviceCatalog/AboutController 等 Bean，这里只验证脱敏静态行为
        String log = "INFO CameraStream - [1072813513] 上游已连接（cs2+udp / chuangmi.camera.039a01）目标 192.168.31.191:32108";
        String s = DiagnosticsService.sanitize(log);
        assertFalse(s.contains("1072813513"));
        assertFalse(s.contains("192.168.31.191"));
        assertTrue(s.contains("107****3513"));
        Map.of("placeholder", true); // 占位避免未使用告警
    }
}
