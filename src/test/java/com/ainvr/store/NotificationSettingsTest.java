package com.ainvr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知推送设置模型测试（v1.2.19）。
 */
class NotificationSettingsTest {

    @Test
    void defaultsAreOffAndSilent() {
        NotificationSettings d = NotificationSettings.DEFAULTS;
        assertFalse(d.enabled());
        assertFalse(d.webhookReady());
        assertFalse(d.mailReady());
        assertFalse(d.inQuietWindow(600)); // 未配置静默时段
        // 默认白名单 motion/offline：其余类型不推送
        assertFalse(d.coversType("anything"));
        assertTrue(d.coversType("motion"));
        assertTrue(d.coversType("OFFLINE")); // 大小写不敏感
    }

    @Test
    void quietWindowSameMinuteMeansDisabled() {
        NotificationSettings s = new NotificationSettings(true, List.of(), 600, 600,
                "", "", false, "", 465, true, "", "", "", "");
        assertFalse(s.inQuietWindow(600));
        assertFalse(s.inQuietWindow(601));
    }

    @Test
    void quietWindowWithinDay() {
        NotificationSettings s = new NotificationSettings(true, List.of(), 420, 540,
                "", "", false, "", 465, true, "", "", "", "");
        assertFalse(s.inQuietWindow(419));
        assertTrue(s.inQuietWindow(420));
        assertTrue(s.inQuietWindow(539));
        assertFalse(s.inQuietWindow(540));
    }

    @Test
    void quietWindowCrossesMidnight() {
        // 22:00(1320) ～ 次日 07:00(420)
        NotificationSettings s = new NotificationSettings(true, List.of(), 1320, 420,
                "", "", false, "", 465, true, "", "", "", "");
        assertTrue(s.inQuietWindow(1320));
        assertTrue(s.inQuietWindow(1439));
        assertTrue(s.inQuietWindow(0));
        assertTrue(s.inQuietWindow(419));
        assertFalse(s.inQuietWindow(420));
        assertFalse(s.inQuietWindow(1319));
    }

    @Test
    void invalidQuietMinutesFallBackToDisabled() {
        NotificationSettings s = new NotificationSettings(true, List.of(), 1500, -7,
                "", "", false, "", 465, true, "", "", "", "");
        assertEquals(-1, s.quietStartMin());
        assertEquals(-1, s.quietEndMin());
        assertFalse(s.inQuietWindow(600));
    }

    @Test
    void portAndTypeSanitized() {
        NotificationSettings s = new NotificationSettings(true, List.of(" Motion ", "", "motion", "OFFLINE"),
                -1, -1, "  http://hook  ", null,
                false, " smtp.x.com ", 99999, true, "", " pw ", "from@x", "A@x, b@x");
        assertEquals(List.of("motion", "offline"), s.types());
        assertEquals("http://hook", s.webhookUrl());
        assertEquals("smtp.x.com", s.smtpHost());
        assertEquals(65535, s.smtpPort());
        assertEquals("pw", s.smtpPass());
    }

    @Test
    void roundTripViaSettingsStore(@TempDir Path dir) {
        AppSettingsStore store = new AppSettingsStore(dir);
        assertEquals(NotificationSettings.DEFAULTS, store.notifySettings());

        NotificationSettings cfg = new NotificationSettings(true, List.of("motion"),
                1320, 420, "https://hook.example", "sec",
                true, "smtp.example.com", 587, false, "u", "p", "f@x", "t@x");
        store.saveNotify(cfg);
        assertEquals(cfg, store.notifySettings());

        // 恢复默认即删除键
        store.saveNotify(NotificationSettings.DEFAULTS);
        assertEquals(NotificationSettings.DEFAULTS, store.notifySettings());
        assertFalse(readFile(dir).contains("\"notify\""), "恢复默认后不应残留 notify 键");
    }

    private static String readFile(Path dir) {
        try {
            return java.nio.file.Files.readString(dir.resolve("settings.json"));
        } catch (Exception e) {
            return "";
        }
    }
}
