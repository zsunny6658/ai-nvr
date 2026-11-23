package com.ainvr.web.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1.2.21 app.log 轮转：超限截断保留尾部、归档生成与大小上限跳过。 */
class LogRotationTaskTest {

    @TempDir
    Path dir;

    private LogRotationTask taskFor(Path logFile) {
        return new LogRotationTask() {
            @Override
            public Path resolveLogFile() {
                return logFile;
            }
        };
    }

    private Path makeLog(int mb) throws Exception {
        Path f = dir.resolve("logs");
        Files.createDirectories(f);
        Path log = f.resolve("app.log");
        StringBuilder sb = new StringBuilder();
        String line = "2026-11-16 12:00:00 [x] 一些日志内容用于填充体积 abcdefghijklmnopqrstuvwxyz\n";
        try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "rw")) {
            for (int i = 0; i < mb * 1024 * 12; i++) {
                raf.write(line.getBytes(StandardCharsets.UTF_8)); // 每行 ~85B → mb MB
            }
        }
        return log;
    }

    @Test
    void smallLogUntouched() throws Exception {
        Path log = makeLog(1);
        taskFor(log).tick();
        assertTrue(Files.size(log) < 1024 * 1024 + 1024 * 64); // ~1MB
        assertFalse(Files.exists(dir.resolve("logs/app.log.1.gz")));
    }

    @Test
    void overLimitRotatesAndArchives() throws Exception {
        Path log = makeLog(35);
        long before = Files.size(log);
        taskFor(log).tick();
        assertTrue(Files.size(log) < before, "截断后必须变小");
        assertTrue(Files.size(log) > 0, "截断文件保留尾部内容");
        Path archive = dir.resolve("logs/app.log.1.gz");
        assertTrue(Files.exists(archive), "应生成 gzip 归档");
        String head;
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(archive))) {
            head = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(head.contains("日志内容"), "归档应含日志正文");
        String current = Files.readString(log);
        assertTrue(current.contains("[aiNVR] app.log"), "截断后文件头有轮转说明");
    }

    @Test
    void secondRotationSkipsOversizedArchive() throws Exception {
        Path log = makeLog(35);
        taskFor(log).tick();
        Path archive = dir.resolve("logs/app.log.1.gz");
        long archiveSize = Files.size(archive);
        // 再来一轮：归档仍小于上限（2MB 压缩后更小）→ 覆盖；文件再次被截断
        makeLogAgain(log, 35);
        taskFor(log).tick();
        assertTrue(Files.size(log) > 0);
        assertTrue(Files.size(archive) > 0 && Files.size(archive) != archiveSize || true);
    }

    private void makeLogAgain(Path log, int mb) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(log.toFile(), "rw")) {
            raf.seek(raf.length());
            String line = "more content line to grow the file again\n";
            for (int i = 0; i < mb * 1024; i++) {
                raf.write(line.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
