package com.ainvr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.2.21 持续变动事件合并：窗口内原位更新 repeat，窗口外新增；合并不触发通知监听。 */
class EventStoreMergeTest {

    @TempDir
    Path dir;

    private static final byte[] FRAME = new byte[3000];

    @Test
    void withinWindowMergesIntoSameId() {
        EventStore store = new EventStore(dir);
        EventStore.AlarmEvent first = store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.20, FRAME, 120_000);
        EventStore.AlarmEvent second = store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.25, FRAME, 120_000);
        EventStore.AlarmEvent third = store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.18, FRAME, 120_000);
        assertEquals(first.id(), second.id());
        assertEquals(first.id(), third.id());
        assertEquals(0, first.repeat());
        assertEquals(2, third.repeat());
        assertEquals(0.25, third.score(), 1e-9); // 分数取历史最高
        assertTrue(third.createdAt() >= second.createdAt(), "时间戳推进到最近一次");
        assertEquals(1, store.list(EventStore.Filter.of("d1", "motion", null, null, null, null, 10, 0)).total());
    }

    @Test
    void outsideWindowCreatesNewEvent() {
        EventStore store = new EventStore(dir);
        EventStore.AlarmEvent first = store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.20, FRAME, 1);
        try {
            Thread.sleep(5);
        } catch (InterruptedException ignored) {
        }
        EventStore.AlarmEvent second = store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.22, FRAME, 1);
        assertNotEquals(first.id(), second.id());
        assertEquals(0, second.repeat());
        assertEquals(2, store.list(EventStore.Filter.of("d1", "motion", null, null, null, null, 10, 0)).total());
    }

    @Test
    void mergeIsPerDevice() {
        EventStore store = new EventStore(dir);
        EventStore.AlarmEvent a = store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.20, FRAME, 600_000);
        EventStore.AlarmEvent b = store.addOrMergeMotion("d2", "书房", "检测到画面变化", 0.21, FRAME, 600_000);
        assertNotEquals(a.id(), b.id());
        assertEquals(0, b.repeat());
    }

    @Test
    void mergeDoesNotFireNotifyListener() {
        EventStore store = new EventStore(dir);
        AtomicLong fired = new AtomicLong();
        store.setAddListener(e -> fired.incrementAndGet());
        store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.20, FRAME, 120_000);
        store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.22, FRAME, 120_000);
        assertEquals(1, fired.get(), "合并路径不应触发通知");
    }

    @Test
    void mergedEventsSurviveReload() {
        EventStore store = new EventStore(dir);
        EventStore.AlarmEvent first = store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.20, FRAME, 120_000);
        store.addOrMergeMotion("d1", "客厅", "检测到画面变化", 0.24, FRAME, 120_000);
        EventStore reloaded = new EventStore(dir);
        assertEquals(1, reloaded.list(EventStore.Filter.of("d1", "motion", null, null, null, null, 10, 0)).total());
        EventStore.AlarmEvent after = reloaded.list(EventStore.Filter.of("d1", "motion", null, null,
                null, null, 10, 0)).items().get(0);
        assertEquals(first.id(), after.id());
        assertEquals(1, after.repeat());
        assertEquals(0.24, after.score(), 1e-9);
    }
}
