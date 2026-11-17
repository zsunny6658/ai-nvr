package com.ainvr.web.service;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.web.api.ApiException;
import com.ainvr.web.config.NvrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1.2.21 容量水位循环覆盖：超限按最旧删除、写中保护、关闭时不动。
 *
 * <p>测试子类覆写 {@link RecordingService#waterLimitBytes(long)} 注入一个小上限，
 * 避免依赖真实磁盘容量。</p>
 */
class RecordingWaterLevelTest {

    private static final String A = "rec-1072813513-001-20261111-120000.mp4";
    private static final String B = "rec-1072813513-002-20261111-120200.mp4";
    private static final String C = "rec-1058566341-003-20261111-120400.mp4";

    @TempDir
    Path dir;

    private AppSettingsStore settings;

    /** 注入固定水位上限（字节）的测试子类。 */
    private static final class TestService extends RecordingService {
        private final Set<String> writing;
        private final long limit;

        TestService(NvrProperties props, AppSettingsStore settings, Set<String> writing, long limit) {
            super(props, null, null, null, settings);
            this.writing = writing;
            this.limit = limit;
        }

        @Override
        protected Set<String> writingSegments() {
            return writing;
        }

        @Override
        protected long waterLimitBytes(long volumeTotal) {
            return limit;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        settings = Mockito.mock(AppSettingsStore.class);
        Files.write(dir.resolve(A), "AAA-1".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(B), "BBB-22".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(C), "CCC-333".getBytes(StandardCharsets.UTF_8));
        // 显式区分新旧（毫秒内连续写入 lastModified 相同，排序不稳定）
        setMtime(A, 0);
        setMtime(B, 60_000);
        setMtime(C, 120_000);
    }

    private void setMtime(String name, long offsetMs) throws IOException {
        Files.setLastModifiedTime(dir.resolve(name),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + offsetMs));
    }

    private TestService service(long limit, Set<String> writing) {
        NvrProperties props = new NvrProperties();
        props.setRecordingDir(dir.toString());
        TestService s = new TestService(props, settings, writing, limit);
        s.setWaterPercentForTest(80); // 打开水位但不触发立即清理（文件仍齐全）
        return s;
    }

    @Test
    void deletesOldestFirstUntilUnderLimit() {
        TestService svc = service(13, Collections.emptySet()); // 上限 13B：删 A(5) 后剩 13B 即达标
        int removed = svc.cleanupByWaterLevel();
        assertEquals(1, removed);
        assertFalse(Files.exists(dir.resolve(A)), "最旧的 A 必须先删");
        assertTrue(Files.exists(dir.resolve(B)));
        assertTrue(Files.exists(dir.resolve(C)), "回落到水位内后停止删除");
    }

    @Test
    void skipsWritingSegments() {
        // 上限 1B：A(5)+B(6)+C(7)=18B，删 A 后剩 13B；B 写入中跳过 → C(7) 也被删；B 因写中保护保留
        TestService svc = service(1, Set.of(B));
        svc.cleanupByWaterLevel();
        assertTrue(Files.exists(dir.resolve(B)), "正在写入的分段不可删");
        assertFalse(Files.exists(dir.resolve(A)));
        assertFalse(Files.exists(dir.resolve(C)));
    }

    @Test
    void disabledWaterLevelDoesNothing() {
        NvrProperties props = new NvrProperties();
        props.setRecordingDir(dir.toString());
        RecordingService svc = new TestService(props, settings, Collections.emptySet(), 1);
        // 未调用 updateWaterPercent → waterPercent=0（关闭）
        assertEquals(0, svc.cleanupByWaterLevel());
        assertTrue(Files.exists(dir.resolve(A)));
        assertTrue(Files.exists(dir.resolve(C)));
    }

    @Test
    void rejectsOutOfRangePercent() {
        RecordingService svc = service(0, Collections.emptySet());
        assertThrows(ApiException.class, () -> svc.updateWaterPercent(96));
        assertThrows(ApiException.class, () -> svc.updateWaterPercent(-1));
    }

    @Test
    void percentPersistedAndCleared() {
        TestService svc = service(80, Collections.emptySet());
        svc.updateWaterPercent(80); // 走真实设置路径触发持久化
        Mockito.verify(settings).saveRecordMaxPercent(80);
        svc.updateWaterPercent(0);
        Mockito.verify(settings).saveRecordMaxPercent(null);
    }
}
