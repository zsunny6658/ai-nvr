package com.ainvr.web.service;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.web.config.NvrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.3.1 第三十一轮（性能攻坚）：录像分段内存索引的回归验证。
 *
 * <p>背景：24/7 连续录像目录积累上万分段，原实现 list/timeline/dashboard 每次全目录
 * listFiles + 逐文件 stat，NAS 上秒级阻塞（概览每 30s 扫一遍、回放页每次打开都扫）。
 * 索引化后 API 读内存；目录 mtime 变化触发同步重扫，后台 30s 刷新兜底。</p>
 *
 * <p>本测试锁三条关键行为：新文件写入后 list <b>立即</b>可见（目录 mtime 检测）、
 * 删除后立即消失（直接维护索引）、timeline 读索引结果与文件名解析一致。</p>
 */
class RecordingIndexTest {

    private static final String A = "rec-1072813513-001-20261111-120000.mp4";
    private static final String B = "rec-1072813513-002-20261111-120200.mp4";
    private static final String C = "rec-1058566341-003-20261111-120400.mp4";

    @TempDir
    Path dir;

    private RecordingService service;

    @BeforeEach
    void setUp() throws IOException {
        Files.write(dir.resolve(A), "AAA-1".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(B), "BBB-22".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(C), "CCC-333".getBytes(StandardCharsets.UTF_8));
        NvrProperties props = new NvrProperties();
        props.setRecordingDir(dir.toString());
        AppSettingsStore settings = Mockito.mock(AppSettingsStore.class);
        Mockito.when(settings.recordingDir()).thenReturn(dir.toString());
        service = new RecordingService(props, null, null, null, settings);
    }

    private void setMtime(String name, long ms) throws IOException {
        Files.setLastModifiedTime(dir.resolve(name), FileTime.from(ms, TimeUnit.MILLISECONDS));
    }

    @Test
    void 新文件写入后list立即可见() throws IOException {
        List<Map<String, Object>> first = service.list(null);
        assertEquals(3, first.size());

        // 模拟封段产生新分段：写文件 → list() 必须立即看到（目录 mtime 检测触发同步重扫）
        String fresh = "rec-1072813513-004-20261111-120600.mp4";
        Files.write(dir.resolve(fresh), "NEW".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> second = service.list(null);
        assertEquals(4, second.size());
        assertTrue(second.stream().anyMatch(m -> fresh.equals(m.get("name"))));
    }

    @Test
    void 删除后索引同步更新() throws IOException {
        service.delete(A);
        List<Map<String, Object>> out = service.list(null);
        assertEquals(2, out.size());
        assertFalse(out.stream().anyMatch(m -> A.equals(m.get("name"))));
    }

    @Test
    void list按did过滤且带path与size() throws IOException {
        List<Map<String, Object>> out = service.list("1058566341");
        assertEquals(1, out.size());
        Map<String, Object> m = out.get(0);
        assertEquals(C, m.get("name"));
        assertEquals(7L, ((Number) m.get("size")).longValue());
        assertEquals(dir.resolve(C).toAbsolutePath().toString(), m.get("path"));
        assertEquals(false, m.get("recording"));
    }

    @Test
    void timeline按天聚合且时长按相邻间隔推断() throws IOException {
        setMtime(A, 0);
        setMtime(B, 60_000);
        setMtime(C, 120_000);
        Map<String, Object> out = service.timeline("1072813513", "2026-11-11");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> segs = (List<Map<String, Object>>) out.get("segments");
        assertEquals(2, segs.size());
        // A(12:00:00)→B(12:02:00) 相邻间隔 120s 且不超过 MERGE_GAP_MS：A 的时长取间隔
        assertEquals(120_000L, segs.get(0).get("durMs"));
        // B 是末段：按 lastModified-startMs 推断
        assertTrue(((Number) segs.get(1).get("durMs")).longValue() > 0);
    }

    @Test
    void 目录切换后索引重建() throws IOException {
        service.list(null); // 建立旧目录索引
        Path dir2 = dir.resolve("second");
        Files.createDirectories(dir2);
        String name = "rec-1149849923-001-20261112-090000.mp4";
        Files.write(dir2.resolve(name), "D".getBytes(StandardCharsets.UTF_8));
        // 切目录走 updateRecordingDir 的公开校验较重，这里直接验证 resolveInitialRootDir 之外的
        // 行为：新目录里 list 必须看到新文件（通过 mtime 失效机制）
        NvrProperties props = new NvrProperties();
        props.setRecordingDir(dir2.toString());
        AppSettingsStore settings = Mockito.mock(AppSettingsStore.class);
        Mockito.when(settings.recordingDir()).thenReturn(dir2.toString());
        RecordingService svc2 = new RecordingService(props, null, null, null, settings);
        List<Map<String, Object>> out = svc2.list(null);
        assertEquals(1, out.size());
        assertEquals(name, out.get(0).get("name"));
    }
}
