package com.ainvr.miss;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 取流档位默认值与覆盖优先级，以及云台命令字。 */
class MissCommandsTest {

    private static final String DID = "1058566341";

    @AfterEach
    void clearOverrides() {
        System.clearProperty("invr.quality." + DID);
        System.clearProperty("invr.quality");
    }

    @Test
    void modelDefaults() {
        // hlc8（CW400）在 HD 档下实测鉴权通过却零媒体包，默认走 SD
        assertEquals("1", MissCommands.defaultQuality("isa.camera.hlc8"));
        assertEquals("2", MissCommands.defaultQuality("chuangmi.camera.079ac1"));
        assertEquals("3", MissCommands.defaultQuality("chuangmi.camera.046c04"));
        assertEquals("3", MissCommands.defaultQuality("chuangmi.camera.72ac1"));
    }

    @Test
    void perDeviceOverrideWinsOverGlobal() {
        System.setProperty("invr.quality", "1");
        System.setProperty("invr.quality." + DID, "3");
        assertEquals("3", MissCommands.qualityFor(DID, "isa.camera.hlc8"));
        assertEquals("1", MissCommands.qualityFor("another-did", "isa.camera.hlc8"));
    }

    @Test
    void invalidOverrideFallsBackToModelDefault() {
        System.setProperty("invr.quality", "9");
        assertEquals("1", MissCommands.qualityFor(DID, "isa.camera.hlc8"));
    }

    @Test
    void motorCommandConstants() {
        assertEquals(0x100, MissCommands.CMD_AUTH_REQ);
        assertEquals(0x102, MissCommands.CMD_VIDEO_START);
        assertEquals(0x103, MissCommands.CMD_VIDEO_STOP);
        assertEquals(0x112, MissCommands.CMD_MOTOR_REQ);
        assertEquals(0x113, MissCommands.CMD_MOTOR_RES);
        assertEquals(0x1001, MissCommands.CMD_ENCODED);
    }

    @Test
    void fixedStepModelsUseOperationPayload() {
        // lalmax-nvr internal/xiaomi/motor.go 的 fixedStepMotorModel 型号表
        assertEquals("operation", MissCommands.motorModeFor("isa.camera.hlc8a"));
        assertEquals("operation", MissCommands.motorModeFor("isa.camera.500dh"));
        assertEquals("operation", MissCommands.motorModeFor("chuangmi.camera.026c05"));
        assertEquals("operation", MissCommands.motorModeFor("xiaomi.camera.082ac1"));
        // 通用机型（含本机两台）走 direction
        assertEquals("direction", MissCommands.motorModeFor("isa.camera.hlc8"));
        assertEquals("direction", MissCommands.motorModeFor("chuangmi.camera.079ac1"));
        assertEquals("direction", MissCommands.motorModeFor(null));
    }
}
