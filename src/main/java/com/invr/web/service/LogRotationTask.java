package com.invr.web.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * v1.2.21 应用日志轮转（针对启动脚本 stdout 重定向的 {@code <运行目录>/logs/app.log}）。
 *
 * <p>启动脚本以 {@code nohup java ... > logs/app.log 2>&1} 方式运行，文件句柄由 shell 持有
 * （O_APPEND），应用内无法 rename 轮转，但可以安全地<strong>截断</strong>：截断后 O_APPEND
 * 的后续写入自然回到文件头部，不会产生空洞。</p>
 *
 * <p>策略：每小时检查一次，超过 {@link #MAX_BYTES}（32MB）时把文件尾部 {@link #TAIL_BYTES}
 * （2MB，保留最近上下文）gzip 归档为 {@code app.log.1.gz}（覆盖旧归档，只保一份），随后把
 * 当前文件截断为这 2MB 尾部内容。归档超过 {@link #MAX_ARCHIVE_BYTES} 时直接丢弃不再归档
 * （旧内容已无排查价值，磁盘优先）。</p>
 */
@Component
public class LogRotationTask {

    private static final Logger log = LoggerFactory.getLogger(LogRotationTask.class);

    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long TAIL_BYTES = 2L * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 8L * 1024 * 1024;
    private static final long INITIAL_DELAY_SECONDS = 120;
    private static final long PERIOD_SECONDS = 3600;

    private final ScheduledExecutorService executor;

    public LogRotationTask() {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "log-rotation");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        executor.scheduleWithFixedDelay(this::tick,
                INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    void tick() {
        try {
            Path logFile = resolveLogFile();
            if (logFile == null || !Files.isRegularFile(logFile)) {
                return;
            }
            long size = Files.size(logFile);
            if (size <= MAX_BYTES) {
                return;
            }
            rotate(logFile, size);
        } catch (Throwable t) {
            log.debug("日志轮转本轮失败：{}", t.toString());
        }
    }

    /** 解析启动脚本重定向的 app.log：优先显式配置，其次 <运行目录>/logs/app.log。 */
    Path resolveLogFile() {
        String override = System.getProperty("invr.log.file");
        if (override != null && !override.isBlank()) {
            return Paths.get(override.trim());
        }
        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path candidate = cwd.resolve("logs").resolve("app.log");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        // 兼容数据目录与运行目录分离的部署：<数据目录>/logs/app.log
        Path alt = com.invr.store.DataDir.get().resolve("logs").resolve("app.log");
        return Files.isRegularFile(alt) ? alt : null;
    }

    private void rotate(Path logFile, long size) throws IOException {
        long keep = Math.min(TAIL_BYTES, size);
        byte[] tail = new byte[(int) keep];
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            raf.seek(size - keep);
            raf.readFully(tail);
        }
        // 对齐到下一个换行开头，避免归档里首行截断半个字符
        int from = 0;
        while (from < tail.length && tail[from] != '\n') {
            from++;
        }
        if (from > 0 && from < tail.length) {
            from++; // 跳过残行
        } else {
            from = 0;
        }
        byte[] body = new byte[tail.length - from];
        System.arraycopy(tail, from, body, 0, body.length);

        Path archive = logFile.resolveSibling(logFile.getFileName() + ".1.gz");
        // 旧归档超限（磁盘优先）则本轮只截断不再覆盖归档；无归档或归档未超限则正常归档
        boolean existingTooBig = Files.isRegularFile(archive)
                && Files.size(archive) >= MAX_ARCHIVE_BYTES;
        boolean archived = false;
        if (!existingTooBig) {
            try (GZIPOutputStream gz = new GZIPOutputStream(Files.newOutputStream(archive))) {
                gz.write(body);
            }
            archived = true;
        }
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "rw")) {
            raf.setLength(0);
            raf.write(("[invr] app.log 于超限（原 " + (size / 1024 / 1024)
                    + "MB）后截断，保留最近上下文；更早内容见 app.log.1.gz（如存在）\n")
                    .getBytes(StandardCharsets.UTF_8));
            raf.write(body);
        }
        log.info("app.log 已轮转：{}MB → {}KB，归档 {}",
                size / 1024 / 1024, body.length / 1024, archived ? "app.log.1.gz" : "本轮跳过");
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
