package com.ainvr.web.service;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v2.3.1 事件快照归档：目录名规整、路径推导与真实复制链路。 */
class SnapshotArchiveServiceTest {

    @TempDir
    Path tmp;

    private static byte[] jpegBytes(long seed) {
        byte[] b = new byte[2048 + (int) (seed % 100)];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (i * 31 + seed);
        }
        return b;
    }

    @Test
    void deviceDirNameSanitizesIllegalChars() {
        assertEquals("客厅摄像头", SnapshotArchiveService.deviceDirName("客厅摄像头", "d1"));
        assertEquals("cam_1", SnapshotArchiveService.deviceDirName("cam/1", "d1"));
        assertEquals("门口_鱼缸", SnapshotArchiveService.deviceDirName("门口:鱼缸", "d1"));
        assertEquals("d9", SnapshotArchiveService.deviceDirName("", "d9"));
        assertEquals("d9", SnapshotArchiveService.deviceDirName(null, "d9"));
        assertEquals("unknown", SnapshotArchiveService.deviceDirName("  ", null));
    }

    @Test
    void expectedArchivePathIsDeterministic() {
        // 2026-11-20 12:34:56 本地时区 → 目录 2026-11-20，文件 123456-42-pet.jpg
        long ts = java.time.LocalDateTime.of(2026, 11, 20, 12, 34, 56)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        EventStore.AlarmEvent e = new EventStore.AlarmEvent(42L, "did", "门口:鱼缸", "pet",
                "info", "t", 0.5, "20261120/42.jpg", ts, false, null, List.of(), 0);
        Path p = SnapshotArchiveService.expectedArchivePath(Path.of("/arch"), e);
        assertTrue(p.toString().contains("2026-11-20"), "日期目录：" + p);
        assertTrue(p.endsWith("123456-42-pet.jpg"), "文件名：" + p);
        // 同一事件两次推导完全一致（无随机成分）
        assertEquals(p, SnapshotArchiveService.expectedArchivePath(Path.of("/arch"), e));
        // base 为空返回 null
        assertFalse(SnapshotArchiveService.expectedArchivePath(null, e) != null);
    }

    @Test
    void archiveCopiesSnapshotIntoLayout() throws Exception {
        Path data = tmp.resolve("data");
        Path arch = tmp.resolve("archive");
        Files.createDirectories(data);
        EventStore store = new EventStore(data);
        AppSettingsStore settings = new AppSettingsStore(data);
        settings.saveSnapshotArchive(true, arch.toString());
        SnapshotArchiveService svc = new SnapshotArchiveService(settings, store);

        byte[] jpeg = jpegBytes(7);
        EventStore.AlarmEvent e = store.add("d1", "客厅", "motion", "info", "有人走动", 0.6, jpeg, List.of());
        // AI 精化为 person → 触发归档监听
        store.refine(e.id(), "person", List.of(Map.of("label", "person", "type", "person", "score", 0.9)));
        // 异步 worker 落盘：轮询等待（上限 3s）
        Path day = arch.resolve(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .format(java.time.LocalDate.now()));
        Path cam = day.resolve(SnapshotArchiveService.deviceDirName("客厅", "d1"));
        Path target = null;
        for (int i = 0; i < 60 && target == null; i++) {
            Thread.sleep(50);
            try (var s = Files.list(cam)) {
                target = s.findFirst().orElse(null);
            } catch (java.io.IOException notYet) {
                // 目录尚未创建
            }
        }
        assertTrue(target != null && Files.isRegularFile(target), "归档文件应存在：" + cam);
        assertEquals(jpeg.length, Files.size(target), "归档内容与源快照一致");
        assertTrue(target.getFileName().toString().endsWith("-" + e.id() + "-person.jpg"));
        // 幂等：同 id 不重复归档
        int count = 0;
        try (var s = Files.list(cam)) {
            count = (int) s.count();
        }
        assertEquals(1, count);

        // 按事件反查路径
        assertTrue(svc.archivedPathOf(e.id()).isPresent());
        assertTrue(svc.archivedPathOf(999999).isEmpty());
    }

    @Test
    void disabledArchiveSkipsCopy() throws Exception {
        Path data = tmp.resolve("data2");
        Path arch = tmp.resolve("archive2");
        Files.createDirectories(data);
        EventStore store = new EventStore(data);
        AppSettingsStore settings = new AppSettingsStore(data);
        // 不启用归档
        settings.saveSnapshotArchive(false, arch.toString());
        new SnapshotArchiveService(settings, store);

        byte[] jpeg = jpegBytes(9);
        EventStore.AlarmEvent e = store.add("d1", "客厅", "motion", "info", "有人", 0.6, jpeg, List.of());
        store.refine(e.id(), "vehicle", List.of());
        Thread.sleep(400);
        assertFalse(Files.exists(arch), "归档关闭时不产生任何文件");
    }
}
