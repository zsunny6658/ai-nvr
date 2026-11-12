package com.invr.hub;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 单设备诊断快照：无会话时返回安全的零值，且绝不新建上游。 */
class StreamHubDescribeTest {

    @Test
    void describeAbsentDeviceReturnsZeroedSnapshot() {
        StreamHub hub = new StreamHub();
        Map<String, Object> m = hub.describe("not-connected");

        assertEquals(false, m.get("active"));
        assertEquals(0, m.get("subscribers"));
        assertEquals(false, m.get("healthy"));
        assertEquals(0, m.get("generation"));
        assertEquals(0L, m.get("lastFrameAt"));
        assertEquals(-1L, m.get("lastFrameAgeMs"));
        assertEquals(0L, m.get("lastErrorAt"));
        assertEquals(0, m.get("noFrameStreak"));
        assertFalse(hub.activeDids().contains("not-connected"));
        assertEquals(0, hub.activeUpstreamCount());
    }
}
