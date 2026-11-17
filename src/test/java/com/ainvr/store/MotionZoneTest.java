package com.ainvr.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排除区域（ROI）模型：归一化坐标夹取、8×8 感知哈希掩码映射与总量限制。
 */
class MotionZoneTest {

    @Test
    void mask64WithNoZonesKeepsAllBlocks() {
        assertEquals(-1L, MotionZone.mask64(List.of()), "无排除区时 64 位应全部参与");
        assertEquals(-1L, MotionZone.mask64(null));
    }

    @Test
    void zoneCoversExactlyItsBlocks() {
        // 右下 1/4 画面（bx 4..7, by 4..7 的块中心都在矩形内）→ 16 个块被排除
        MotionZone z = new MotionZone(0.5, 0.5, 0.5, 0.5);
        long mask = MotionZone.mask64(List.of(z));
        assertEquals(48, Long.bitCount(mask), "右下 1/4 应排除 16 个块");
        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                boolean excluded = bx >= 4 && by >= 4;
                assertEquals(!excluded, (mask & (1L << (by * 8 + bx))) != 0,
                        "块 (" + bx + "," + by + ") 掩码位错误");
            }
        }
    }

    @Test
    void hashBitOrderMatchesBlockLayout() {
        // 只排除左上角一个块（中心 (0.5/8, 0.5/8) 在 (0,0,0.125,0.125) 内）
        MotionZone z = new MotionZone(0, 0, 0.125, 0.125);
        long mask = MotionZone.mask64(List.of(z));
        assertEquals(63, Long.bitCount(mask));
        assertEquals(0, (int) (mask & 1L), "位 0（块 0,0）应被排除");
        assertEquals(1L, mask >>> 1 & 1L, "位 1（块 1,0）应保留");
    }

    @Test
    void coordinatesAreClampedAndFloorSizeEnforced() {
        MotionZone z = new MotionZone(-0.5, 1.5, 0.001, 2.0);
        assertEquals(0.0, z.x());
        // y=1.5 夹到 1.0 后整体越界，回拉到画面内并保住最小尺寸
        assertEquals(0.98, z.y());
        assertEquals(0.02, z.w(), 1e-9);
        assertEquals(0.02, z.h(), 1e-9);
    }

    @Test
    void outOfBoundsRectIsPulledBackIntoFrame() {
        MotionZone z = new MotionZone(0.9, 0.9, 0.5, 0.5);
        assertTrue(z.x() + z.w() <= 1.0 + 1e-9);
        assertTrue(z.y() + z.h() <= 1.0 + 1e-9);
        assertTrue(z.w() >= 0.02 && z.h() >= 0.02);
    }

    @Test
    void sanitizeCapsZoneCountAndDropsNulls() {
        List<MotionZone> in = new ArrayList<>();
        in.add(null);
        for (int i = 0; i < 12; i++) {
            in.add(new MotionZone(0.5, 0.5, 0.1, 0.1));
        }
        List<MotionZone> out = MotionZone.sanitize(in);
        assertEquals(MotionZone.MAX_ZONES, out.size());
        assertTrue(out.stream().allMatch(java.util.Objects::nonNull), "不应含 null 区域");
    }

    @Test
    void totalAreaSumsRectangles() {
        assertEquals(0.25, MotionZone.totalArea(
                List.of(new MotionZone(0, 0, 0.5, 0.5))), 1e-9);
        assertEquals(0.26, MotionZone.totalArea(List.of(
                new MotionZone(0, 0, 0.5, 0.5), new MotionZone(0.5, 0, 0.1, 0.1))), 1e-9);
    }
}
