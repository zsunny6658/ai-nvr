package com.ainvr.store;

import com.ainvr.store.EventStore.AlarmEvent;
import com.ainvr.store.EventStore.Filter;
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
    void purgeBrokenSnapshotsKeepsNormalFramesAndEventsWithoutSnapshot() throws Exception {
        EventStore s = store();
        byte[] normal = frame("normal-corridor.jpg");
        byte[] broken = frame("broken-residual.jpg");
        AlarmEvent good = s.add("d1", "走廊", "motion", "info", "正常画面", 0.4, normal, List.of());
        AlarmEvent bad = s.add("d1", "走廊", "motion", "info", "残缺帧", 0.9, broken, List.of());
        AlarmEvent noSnap = s.add("d2", "门口", "offline", "warn", "设备离线", 0.0, null, List.of());
        assertEquals(3, s.size());

        Path badFile = dir.resolve("events/snapshots").resolve(bad.snapshot());
        assertTrue(Files.isRegularFile(badFile));

        assertEquals(1, s.purgeBrokenSnapshots());
        assertEquals(2, s.size());
        assertTrue(s.find(good.id()).isPresent(), "正常画面事件必须保留");
        assertTrue(s.find(noSnap.id()).isPresent(), "无快照事件必须保留");
        assertTrue(s.find(bad.id()).isEmpty());
        assertFalse(Files.exists(badFile), "残缺快照文件应同步删除");

        // 一次性重写日志后重启仍一致
        EventStore reloaded = store();
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.find(bad.id()).isEmpty());
        assertEquals(0, reloaded.purgeBrokenSnapshots(), "重复清理应无副作用");

        // 首页卡片聚合不应再把残缺帧挑出来
        Map<String, List<AlarmEvent>> recent = reloaded.recentWithSnapshots(3);
        assertTrue(recent.getOrDefault("d1", List.of()).stream()
                .noneMatch(e -> e.id() == bad.id()));
    }

    private static byte[] frame(String name) throws Exception {
        try (var in = EventStoreTest.class.getResourceAsStream("/frames/" + name)) {
            if (in == null) {
                throw new IllegalStateException("缺少测试样本：" + name);
            }
            return in.readAllBytes();
        }
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

    /** v1.2.15：批量删除 / 条件清空 / 全量清空后，重写日志重放结果必须与内存一致。 */
    @Test
    void bulkDeleteAndClearAreAtomicAndReplayable() throws Exception {
        EventStore s = store();
        for (int i = 0; i < 8; i++) {
            s.add(i < 4 ? "d1" : "d2", "设备", "motion", "info", "e" + i, 0.2, JPEG, List.of());
        }
        assertEquals(2, s.deleteBatch(List.of(1L, 3L, 3L, 999L)));
        assertEquals(6, s.size());
        assertEquals(6, store().size());
        assertNull(s.snapshot(1));

        assertEquals(2, s.markHandledBatch(List.of(2L, 4L, 777L), true));
        EventStore midway = store();
        assertTrue(midway.find(2).orElseThrow().handled());
        assertTrue(midway.find(4).orElseThrow().handled());

        // 只清 d2，d1 保留
        assertEquals(4, s.clearMatching(Filter.of("d2", null, null, null, null, null, 20, 0)));
        assertEquals(2, s.size());
        assertEquals(2, store().size());

        // 全量清空：日志写出空文件，重启不会复活
        assertEquals(2, s.clearMatching(null));
        assertEquals(0, s.size());
        assertEquals(0, store().size());
        assertEquals(0, s.list(all()).total());
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
