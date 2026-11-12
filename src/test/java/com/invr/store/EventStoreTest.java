package com.invr.store;

import com.invr.store.EventStore.AlarmEvent;
import com.invr.store.EventStore.Filter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事件库：JSONL 重放、快照读写、筛选统计、清理与损坏输入容错。 */
class EventStoreTest {

    @TempDir
    Path dir;

    private static final byte[] JPEG = "FAKE-JPEG".getBytes(StandardCharsets.UTF_8);

    private EventStore store() {
        return new EventStore(dir);
    }

    private static Filter all() {
        return Filter.of(null, null, null, null, null, null, 20, 0);
    }

    @Test
    void addPersistsAcrossRestartWithSnapshot() throws Exception {
        EventStore s = store();
        AlarmEvent e = s.add("d1", "客厅", "motion", "info", "检测到画面变化",
                0.25, JPEG, List.of());
        assertEquals(1, e.id());
        assertFalse(e.handled());
        assertFalse(e.snapshot().isEmpty());
        assertArrayEquals(JPEG, s.snapshot(e.id()));

        EventStore reloaded = store();
        assertEquals(1, reloaded.size());
        AlarmEvent back = reloaded.find(1).orElseThrow();
        assertEquals("d1", back.did());
        assertEquals(0.25, back.score());
        assertEquals(e.snapshot(), back.snapshot());
        assertArrayEquals(JPEG, reloaded.snapshot(1));
        assertEquals(2, reloaded.add("d1", "客厅", "motion", "info", "第二次",
                0.3, null, List.of()).id());
    }

    @Test
    void markHandledAndDeleteAreReplayed() throws Exception {
        EventStore s = store();
        s.add("d1", "客厅", "motion", "info", "事件", 0.4, JPEG, List.of());

        assertTrue(s.markHandled(1, true).orElseThrow().handled());
        assertTrue(s.markHandled(1, true).orElseThrow().handledAt() > 0);
        EventStore midway = store();
        assertTrue(midway.find(1).orElseThrow().handled());
        assertFalse(s.markHandled(1, false).orElseThrow().handled());
        assertNull(s.find(1).orElseThrow().handledAt());

        assertTrue(s.delete(1));
        assertFalse(s.delete(1));
        assertEquals(0, s.size());
        assertNull(s.snapshot(1));
        assertEquals(0, store().size());
        assertTrue(s.markHandled(1, true).isEmpty());
    }

    @Test
    void filtersAndStats() {
        EventStore s = store();
        s.add("d1", "客厅", "motion", "info", "a", 0.2, null, List.of());
        s.add("d2", "门口", "motion", "info", "b", 0.3, null, List.of());
        s.add("d1", "客厅", "person", "warning", "c", 0.9, null, List.of(Map.of("label", "person")));
        s.markHandled(3, true);

        assertEquals(3, s.list(all()).total());
        assertEquals(2, s.list(Filter.of("d1", null, null, null, null, null, 20, 0)).total());
        assertEquals(1, s.list(Filter.of(null, "person", null, null, null, null, 20, 0)).total());
        assertEquals(1, s.list(Filter.of(null, null, true, null, null, null, 20, 0)).total());
        assertEquals(2, s.list(Filter.of(null, null, false, null, null, null, 20, 0)).total());
        assertEquals(3, s.list(Filter.of(null, null, null,
                java.time.LocalDate.now().toString(), null, null, 20, 0)).total());
        assertEquals(2, s.list(Filter.of(null, null, null, null, null, null, 2, 0)).items().size());
        assertEquals(3, s.list(Filter.of(null, null, null, null, null, null, 2, 2)).total());
        assertEquals(1, s.list(Filter.of(null, null, null, null, null, null, 2, 2)).items().size());
        assertEquals(3, s.list(all()).items().get(0).id()); // 倒序

        Map<String, Object> stats = s.stats();
        assertEquals(3, stats.get("total"));
        assertEquals(2, stats.get("unhandled"));
        assertEquals(3, stats.get("today"));
    }

    @Test
    void purgeKeepsNewestWithinLimit() {
        EventStore s = store();
        for (int i = 0; i < 105; i++) {
            s.add("d1", "客厅", "motion", "info", "e" + i, 0.2, null, List.of());
        }
        assertEquals(105, s.size());
        assertEquals(5, s.purge(100, 0));
        assertEquals(100, s.size());
        assertEquals(105, s.list(all()).items().get(0).id());
        assertEquals(100, store().size()); // 重放后仍一致
    }

    @Test
    void corruptLinesAreSkippedWithoutBreakingStartup() throws Exception {
        Path log = dir.resolve("events").resolve("events.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "not-json\n\n{\"op\":\"unknown\"}\n", StandardCharsets.UTF_8);
        assertEquals(0, store().size());
        assertEquals(1, store().add("d1", "客厅", "motion", "info", "ok",
                0.2, null, List.of()).id());
    }

    @Test
    void snapshotPathTraversalIsRejected() throws Exception {
        Path log = dir.resolve("events").resolve("events.jsonl");
        Files.createDirectories(log.getParent());
        String evil = "{\"op\":\"add\",\"event\":{\"id\":42,\"did\":\"d1\",\"deviceName\":\"x\","
                + "\"type\":\"motion\",\"level\":\"info\",\"title\":\"x\",\"score\":0.5,"
                + "\"snapshot\":\"../../outside.jpg\",\"createdAt\":1,\"handled\":false,"
                + "\"handledAt\":null,\"detections\":[]}}\n";
        Files.writeString(log, evil, StandardCharsets.UTF_8);
        EventStore s = store();
        assertEquals(1, s.size());
        assertNull(s.snapshot(42));
    }
}
