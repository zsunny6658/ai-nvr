package com.invr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运动侦测参数的默认值、越界夹取与持久化。 */
class MotionSettingsTest {

    @TempDir
    Path dir;

    @Test
    void defaultsAreDisabledAndSafe() {
        MotionSettings d = MotionSettings.DEFAULTS;
        assertFalse(d.enabled());
        assertEquals(0.12, d.threshold());
        assertEquals(2, d.intervalSec());
        assertEquals(30, d.cooldownSec());
        assertEquals(2, d.confirmFrames());
        assertTrue(d.dids().isEmpty());
        assertTrue(d.covers("any-did"));
    }

    @Test
    void outOfRangeValuesAreClampedAndDidsDeduplicated() {
        MotionSettings s = new MotionSettings(true, 9.0, 0, 1, 0, 999, 999, 1,
                java.util.Arrays.asList(" a ", "", null, "a", "b"));
        assertEquals(0.50, s.threshold());
        assertEquals(1, s.intervalSec());
        assertEquals(5, s.cooldownSec());
        assertEquals(1, s.confirmFrames());
        assertEquals(120, s.silentSec());
        assertEquals(365, s.retentionDays());
        assertEquals(100, s.maxEvents());
        assertEquals(List.of("a", "b"), s.dids());
        assertTrue(s.covers("a"));
        assertFalse(s.covers("c"));

        MotionSettings nan = new MotionSettings(false, Double.NaN, 2, 30, 2, 8, 30, 2000, null);
        assertEquals(0.02, nan.threshold());
        assertTrue(nan.dids().isEmpty());
    }

    @Test
    void persistsAcrossRestartAndCanResetToDefaults() {
        AppSettingsStore store = new AppSettingsStore(dir);
        assertEquals(MotionSettings.DEFAULTS, store.motion());

        MotionSettings custom = new MotionSettings(true, 0.2, 5, 60, 3, 15, 7, 500,
                List.of("d1", "d2"));
        store.saveMotion(custom);
        AppSettingsStore reloaded = new AppSettingsStore(dir);
        assertEquals(custom, reloaded.motion());

        reloaded.saveMotion(MotionSettings.DEFAULTS);
        assertEquals(MotionSettings.DEFAULTS, new AppSettingsStore(dir).motion());
    }

    @Test
    void missingFieldsFallBackToDefaults() {
        AppSettingsStore store = new AppSettingsStore(dir);
        // 只打开开关，其余字段缺失时逐项回落默认值
        store.saveMotion(new MotionSettings(true, 0.12, 2, 30, 2, 8, 30, 2000, List.of()));
        MotionSettings m = new AppSettingsStore(dir).motion();
        assertTrue(m.enabled());
        assertEquals(0.12, m.threshold());
        assertEquals(30, m.cooldownSec());
    }
}
