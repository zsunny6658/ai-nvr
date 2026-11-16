package com.invr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 按设备清晰度覆盖与云台命令格式的持久化（重启后仍生效 / 可恢复默认）。 */
class AppSettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void deviceQualityPersistsAndCanBeReset() {
        AppSettingsStore store = new AppSettingsStore(dir);
        assertNull(store.qualityFor("d1"));

        store.saveDeviceQuality("d1", "2");
        store.saveDeviceQuality("d2", "SD");
        AppSettingsStore reloaded = new AppSettingsStore(dir);
        assertEquals("2", reloaded.qualityFor("d1"));
        assertEquals("sd", reloaded.qualityFor("d2"));
        assertEquals(2, reloaded.deviceQuality().size());

        store.saveDeviceQuality("d1", "default");
        assertNull(new AppSettingsStore(dir).qualityFor("d1"));
        assertEquals("sd", new AppSettingsStore(dir).qualityFor("d2"));
    }

    @Test
    void ptzModeDefaultsToAutoAndSupportsOverride() {
        AppSettingsStore store = new AppSettingsStore(dir);
        assertEquals("auto", store.ptzMode());
        store.savePtzMode("operation");
        assertEquals("operation", new AppSettingsStore(dir).ptzMode());
        store.savePtzMode("direction");
        assertEquals("direction", new AppSettingsStore(dir).ptzMode());
        store.savePtzMode("auto");
        assertEquals("auto", new AppSettingsStore(dir).ptzMode());
        store.savePtzMode("bad-value");
        assertEquals("auto", new AppSettingsStore(dir).ptzMode());
    }

    @Test
    void otherSettingsAreNotOverwritten() {
        AppSettingsStore store = new AppSettingsStore(dir);
        store.saveHls(3, 8, 30, true, false);
        store.saveDeviceQuality("d1", "1");
        assertEquals(Integer.valueOf(30), new AppSettingsStore(dir).hlsIdleSec());
        assertEquals("1", new AppSettingsStore(dir).qualityFor("d1"));
    }

    /** v1.2.22 RTSP 访问令牌：设置 / 重启读回 / 清除，且不影响其他键。 */
    @Test
    void rtspTokenPersistsAndCanBeCleared() {
        AppSettingsStore store = new AppSettingsStore(dir);
        assertNull(store.rtspToken());

        store.saveRtspToken(" my-secret ");
        assertEquals("my-secret", new AppSettingsStore(dir).rtspToken());

        // 清除（null / 空白 / "-" 由调用方归一为 null）后其他设置不受影响
        store.saveHls(3, 8, 30, true, false);
        store.saveRtspToken(null);
        assertNull(new AppSettingsStore(dir).rtspToken());
        assertEquals(Integer.valueOf(30), new AppSettingsStore(dir).hlsIdleSec());

        store.saveRtspToken("tok2");
        store.saveRtspToken("   ");
        assertNull(new AppSettingsStore(dir).rtspToken());
    }
}
