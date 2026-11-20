package com.ainvr.miss;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双摄第二镜头 DID 解析与机型识别。
 */
class DualLensTest {

    @Test
    void secondaryDidRoundTrip() {
        assertEquals("123456#2", DualLens.secondaryDid("123456"));
        assertTrue(DualLens.isSecondary("123456#2"));
        assertFalse(DualLens.isSecondary("123456"));
        assertEquals("123456", DualLens.baseDid("123456#2"));
        assertEquals("123456", DualLens.baseDid("123456"));
    }

    @Test
    void nullAndEdgeInputs() {
        assertFalse(DualLens.isSecondary(null));
        assertEquals(null, DualLens.baseDid(null));
        assertFalse(DualLens.knownDualLensModel(null));
        assertFalse(DualLens.knownDualLensModel("chuangmi.camera.029a02"));
        assertTrue(DualLens.knownDualLensModel("isa.camera.hlmax"));
        assertTrue(DualLens.knownDualLensModel("xiaomi.camera.082ac1"));
    }
}
