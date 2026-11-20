package com.ainvr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v2.3.1 MQTT / Home Assistant 设置存取（含密码保持语义与字段夹取）。 */
class MqttSettingsTest {

    @TempDir
    Path tmp;

    @Test
    void defaultsWhenUnconfigured() {
        AppSettingsStore s = new AppSettingsStore(tmp);
        Map<String, Object> m = s.mqtt();
        assertEquals(false, m.get("enabled"));
        assertEquals("", m.get("host"));
        assertEquals(1883, m.get("port"));
        assertEquals("homeassistant", m.get("discoveryPrefix"));
        assertEquals("ainvr", m.get("baseTopic"));
        assertEquals(90, m.get("offDelaySec"));
        assertEquals(30, m.get("snapshotIntervalSec"));
    }

    @Test
    void saveThenLoadRoundTrip() {
        AppSettingsStore s = new AppSettingsStore(tmp);
        Map<String, Object> in = new HashMap<>();
        in.put("enabled", true);
        in.put("host", " 192.168.31.10 ");
        in.put("port", 8883);
        in.put("username", "ha");
        in.put("password", "pw1");
        in.put("tls", true);
        in.put("offDelaySec", 120);
        in.put("snapshotIntervalSec", 0);
        s.saveMqtt(in);

        Map<String, Object> m = s.mqtt();
        assertEquals(true, m.get("enabled"));
        assertEquals("192.168.31.10", m.get("host"));
        assertEquals(8883, m.get("port"));
        assertEquals("ha", m.get("username"));
        assertEquals("pw1", m.get("password"));
        assertEquals(true, m.get("tls"));
        assertEquals(120, m.get("offDelaySec"));
        assertEquals(0, m.get("snapshotIntervalSec"));
    }

    @Test
    void updateWithoutPasswordKeepsStoredSecret() {
        AppSettingsStore s = new AppSettingsStore(tmp);
        Map<String, Object> in = new HashMap<>();
        in.put("enabled", false);
        in.put("host", "broker.local");
        in.put("password", "secret");
        s.saveMqtt(in);

        // 前端掩码不回显：更新其它字段时密码不随请求体传来，必须保持
        Map<String, Object> upd = new HashMap<>();
        upd.put("username", "ha");
        s.saveMqtt(upd);
        assertEquals("secret", s.mqtt().get("password"));

        // 显式传空串 = 清除
        Map<String, Object> clear = new HashMap<>();
        clear.put("password", "");
        s.saveMqtt(clear);
        assertEquals("", s.mqtt().get("password"));
    }

    @Test
    void invalidValuesAreClamped() {
        AppSettingsStore s = new AppSettingsStore(tmp);
        Map<String, Object> in = new HashMap<>();
        in.put("port", 99999);
        in.put("offDelaySec", 1);
        in.put("snapshotIntervalSec", -5);
        in.put("baseTopic", "a i n v r!");
        s.saveMqtt(in);
        Map<String, Object> m = s.mqtt();
        assertEquals(65535, m.get("port"));
        assertEquals(10, m.get("offDelaySec"));
        assertEquals(0, m.get("snapshotIntervalSec"));
        assertEquals("ainvr", m.get("baseTopic"));
    }

    @Test
    void enableWithoutHostIsForcedOff() {
        AppSettingsStore s = new AppSettingsStore(tmp);
        Map<String, Object> in = new HashMap<>();
        in.put("enabled", true);
        s.saveMqtt(in);
        assertFalse((Boolean) s.mqtt().get("enabled"));

        in.put("host", "h");
        s.saveMqtt(in);
        assertTrue((Boolean) s.mqtt().get("enabled"));
    }

    @Test
    void mqttKeySurvivesImport() {
        AppSettingsStore s = new AppSettingsStore(tmp);
        Map<String, Object> in = new HashMap<>();
        in.put("host", "h1");
        s.saveMqtt(in);
        // 导入清理白名单应包含 mqtt 键（备份导入不丢配置）
        Map<String, Object> payload = new HashMap<>();
        payload.put("mqtt", s.all().get("mqtt"));
        var imported = s.importAll(payload);
        assertTrue(imported.contains("mqtt"));
    }
}
