package com.invr.store;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.2.21 侦测时段计划：跨午夜窗口与未启用时全天放行。 */
class MotionScheduleTest {

    private static final ZoneOffset Z = ZoneOffset.ofHours(8);

    private static long at(int hour, int minute) {
        return LocalDate.of(2026, 11, 16).atTime(LocalTime.of(hour, minute))
                .toInstant(Z).toEpochMilli();
    }

    private MotionSettings scheduled(int startMin, int endMin) {
        return new MotionSettings(true, 0.12, 2, 30, 2, 8, 30, 2000,
                List.of(), false, 20, true, startMin, endMin);
    }

    @Test
    void disabledScheduleIsAlwaysActive() {
        MotionSettings off = new MotionSettings(true, 0.12, 2, 30, 2, 8, 30, 2000,
                List.of(), false, 20, false, 22 * 60, 6 * 60);
        assertTrue(off.inActiveWindow(at(9, 30)));
    }

    @Test
    void sameStartEndMeansAlwaysActive() {
        assertTrue(scheduled(600, 600).inActiveWindow(at(9, 30)));
    }

    @Test
    void normalWindow() {
        MotionSettings day = scheduled(8 * 60, 18 * 60); // 08:00~18:00
        assertTrue(day.inActiveWindow(at(9, 30)));
        assertTrue(day.inActiveWindow(at(8, 0)));
        assertFalse(day.inActiveWindow(at(6, 30)));
        assertFalse(day.inActiveWindow(at(19, 30)));
    }

    @Test
    void crossMidnightWindow() {
        MotionSettings night = scheduled(22 * 60, 6 * 60); // 22:00~06:00 跨午夜
        assertTrue(night.inActiveWindow(at(5, 30)));
        assertTrue(night.inActiveWindow(at(23, 30)));
        assertTrue(night.inActiveWindow(at(0, 10)));
        assertFalse(night.inActiveWindow(at(9, 30)));
        assertFalse(night.inActiveWindow(at(6, 30))); // 端点语义：[start, end)
    }

    @Test
    void clampsOutOfRangeMinutes() {
        MotionSettings s = scheduled(-5, 1500);
        assertTrue(s.activeStartMin() >= 0);
        assertTrue(s.activeEndMin() <= 1440);
    }
}
