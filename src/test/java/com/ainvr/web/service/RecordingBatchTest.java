package com.ainvr.web.service;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.web.api.ApiException;
import com.ainvr.web.config.NvrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.2.2 录像批量操作：批量删除的逐条容错 / 录制中保护、批量打包 zip 的内容正确性。
 *
 * <p>构造 RecordingService 时只依赖 AppSettingsStore 的三个读取方法（用 mock 返回 null 走配置默认值）；
 * StreamHub / CameraConnector / RecordingStateStore 在被测方法中完全不参与，传 null 即可。
 * {@link RecordingService#writingSegments()} 用子类覆写来模拟「正在写入的分段」。</p>
 */
class RecordingBatchTest {

    private static final String A = "rec-1072813513-001-20261111-120000.mp4";
    private static final String B = "rec-1072813513-002-20261111-120200.mp4";
    private static final String C = "rec-1058566341-003-20261111-120400.mp4";

    @TempDir
    Path dir;

    private RecordingService svc;

    /** 可注入「正在写入分段」的测试子类。 */
    private static final class TestService extends RecordingService {
        private final Set<String> writing;

        TestService(NvrProperties props, AppSettingsStore settings, Set<String> writing) {
            super(props, null, null, null, settings);
            this.writing = writing;
        }

        @Override
        protected Set<String> writingSegments() {
            return writing;
        }
    }

    private RecordingService service(Set<String> writing) throws IOException {
        NvrProperties props = new NvrProperties();
        props.setRecordingDir(dir.toString());
        AppSettingsStore settings = Mockito.mock(AppSettingsStore.class);
        TestService s = new TestService(props, settings, writing);
        Files.write(dir.resolve(A), "AAA-1".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(B), "BBB-22".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(C), "CCC-333".getBytes(StandardCharsets.UTF_8));
        return s;
    }

    @BeforeEach
    void setUp() throws IOException {
        svc = service(Collections.emptySet());
    }

    @Test
    void batchDeleteRemovesOnlySelectedFiles() {
        Map<String, Object> r = svc.deleteBatch(List.of(A, B));

        assertEquals(2, r.get("deletedCount"));
        assertEquals(0, r.get("skippedCount"));
        assertEquals(0, r.get("failedCount"));
        assertFalse(Files.exists(dir.resolve(A)));
        assertFalse(Files.exists(dir.resolve(B)));
        assertTrue(Files.exists(dir.resolve(C)), "未勾选的文件不能被删除");
    }

    @Test
    void batchDeleteReportsBadNamesAndMissingFilesPerItem() {
        Map<String, Object> r = svc.deleteBatch(Arrays.asList(A, "../evil.mp4", "rec-1-999-20261111-999999.mp4"));

        assertEquals(1, r.get("deletedCount"));
        assertEquals(2, r.get("failedCount"));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> failed = (List<Map<String, String>>) r.get("failed");
        assertTrue(failed.stream().anyMatch(f -> "../evil.mp4".equals(f.get("name"))));
        assertTrue(failed.stream().allMatch(f -> f.get("reason") != null && !f.get("reason").isBlank()));
        assertTrue(Files.exists(dir.resolve(B)));
        assertTrue(Files.exists(dir.resolve(C)));
    }

    @Test
    void batchDeleteSkipsWritingSegment() throws IOException {
        RecordingService withWriting = service(Set.of(B));

        Map<String, Object> r = withWriting.deleteBatch(List.of(A, B, C));

        assertEquals(2, r.get("deletedCount"));
        assertEquals(1, r.get("skippedCount"));
        assertEquals(List.of(B), r.get("skipped"));
        assertTrue(Files.exists(dir.resolve(B)), "正在写入的分段必须保留");
        assertFalse(Files.exists(dir.resolve(A)));
        assertFalse(Files.exists(dir.resolve(C)));
    }

    @Test
    void singleDeleteAlsoRejectsWritingSegment() throws IOException {
        RecordingService withWriting = service(Set.of(A));

        ApiException e = assertThrows(ApiException.class, () -> withWriting.delete(A));
        assertEquals(409, e.getStatus());
        assertTrue(Files.exists(dir.resolve(A)));
    }

    @Test
    void batchDeleteRejectsEmptyAndOversizedRequests() {
        ApiException empty = assertThrows(ApiException.class,
                () -> svc.deleteBatch(Collections.emptyList()));
        assertEquals(400, empty.getStatus());

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i <= RecordingService.MAX_BATCH_DELETE; i++) {
            tooMany.add(A);
        }
        ApiException oversized = assertThrows(ApiException.class, () -> svc.deleteBatch(tooMany));
        assertEquals(400, oversized.getStatus());
        assertTrue(Files.exists(dir.resolve(A)));
    }

    @Test
    void zipContainsExactlySelectedFilesWithOriginalContent() throws IOException {
        RecordingService.BatchPlan plan = svc.planZip(List.of(A, C));

        assertEquals(2, plan.size());
        assertEquals(0, plan.skippedWriting());
        assertEquals(0, plan.skippedUnavailable());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(2, svc.writeZip(plan, out));

        Map<String, byte[]> entries = readZip(out.toByteArray());
        assertEquals(Set.of(A, C), entries.keySet());
        assertArrayEquals("AAA-1".getBytes(StandardCharsets.UTF_8), entries.get(A));
        assertArrayEquals("CCC-333".getBytes(StandardCharsets.UTF_8), entries.get(C));
        // 打包不改动源文件
        assertTrue(Files.exists(dir.resolve(A)));
        assertTrue(Files.exists(dir.resolve(B)));
    }

    @Test
    void zipSkipsWritingAndUnavailableFilesButStillSucceeds() throws IOException {
        RecordingService withWriting = service(Set.of(B));

        RecordingService.BatchPlan plan = withWriting.planZip(
                Arrays.asList(A, B, "../evil.mp4", "rec-1-999-20261111-999999.mp4", C, C));

        assertEquals(2, plan.size(), "重复名字去重后再打包");
        assertEquals(1, plan.skippedWriting());
        assertTrue(plan.skippedUnavailable() >= 2);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(2, withWriting.writeZip(plan, out));
        assertEquals(Set.of(A, C), readZip(out.toByteArray()).keySet());
    }

    @Test
    void zipRejectsWhenNothingCanBePacked() throws IOException {
        RecordingService withWriting = service(Set.of(A));
        ApiException e = assertThrows(ApiException.class,
                () -> withWriting.planZip(List.of(A, "../evil.mp4")));
        assertEquals(400, e.getStatus());
    }

    private static Map<String, byte[]> readZip(byte[] bytes) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                out.put(entry.getName(), zin.readAllBytes());
            }
        }
        return out;
    }
}
