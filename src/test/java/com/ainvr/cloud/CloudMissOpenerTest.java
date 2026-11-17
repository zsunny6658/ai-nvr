package com.ainvr.cloud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用户在 Web 端固定档位后不再被自动降级改写；空值/default 保持自动。 */
class CloudMissOpenerTest {

    @Test
    void fixedQualities() {
        assertTrue(CloudMissOpener.isFixedQuality("0"));
        assertTrue(CloudMissOpener.isFixedQuality("1"));
        assertTrue(CloudMissOpener.isFixedQuality("2"));
        assertTrue(CloudMissOpener.isFixedQuality("3"));
        assertTrue(CloudMissOpener.isFixedQuality("SD"));
        assertTrue(CloudMissOpener.isFixedQuality("hd"));
        assertTrue(CloudMissOpener.isFixedQuality("auto"));
    }

    @Test
    void automaticQualities() {
        assertFalse(CloudMissOpener.isFixedQuality(null));
        assertFalse(CloudMissOpener.isFixedQuality(""));
        assertFalse(CloudMissOpener.isFixedQuality("  "));
        assertFalse(CloudMissOpener.isFixedQuality("default"));
        assertFalse(CloudMissOpener.isFixedQuality("9"));
    }
}
