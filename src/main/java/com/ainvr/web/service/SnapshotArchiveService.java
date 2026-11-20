package com.ainvr.web.service;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import com.ainvr.store.EventStore.AlarmEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * v2.3.1 事件快照归档：AI 命中（person / vehicle / pet）的事件快照，按
 * {@code 归档目录/日期/摄像头名/} 结构复制一份到用户选择的授权目录。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><strong>复制而非移动</strong>：EventStore 的快照仍是事件生命周期的一部分
 *       （删除事件会同步清理），归档副本独立存在，删事件不丢归档；</li>
 *   <li><strong>AI 精化后触发</strong>：person/vehicle/pet 类型只会由本地 AI 检测精化产生
 *       （motion → refine），所以监听 {@link EventStore#addRefineListener} 一个点即可覆盖全部
 *       归档目标事件；精化发生在事件产生后数百毫秒，此时快照文件已落盘；</li>
 *   <li><strong>异步单线程</strong>：归档在独立单线程池执行，复制失败只记日志，绝不影响事件主链路；
 *       NAS 慢盘上连续 AI 事件会排队，队列有界（512）防内存膨胀，满了直接丢弃本条并告警；</li>
 *   <li><strong>目录名确定性</strong>：归档文件名由事件 id / 创建时间 / 类型推导（见
 *       {@link #expectedArchivePath}），供「在文件管理中打开」按事件反查，无需额外索引。</li>
 * </ul>
 *
 * <p>设备目录名取设备名（文件系统非法字符替换为下划线），设备名为空时回落到 did；
 * 文件名 {@code HHmmss-{id}-{type}.jpg}，同 id 只归档一次（内存去重，重启后靠文件存在性幂等）。</p>
 */
@Service
public class SnapshotArchiveService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotArchiveService.class);

    /** 归档目标事件类型（与 AI 检测的精化产物一致）。 */
    public static final Set<String> ARCHIVED_TYPES = Set.of("person", "vehicle", "pet");

    private static final DateTimeFormatter DAY_DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("HHmmss");
    private static final int QUEUE_CAP = 512;

    private final AppSettingsStore settingsStore;
    private final EventStore events;

    /** 单线程异步归档；IO 全部隔离在该线程，主链路零等待。 */
    private final ExecutorService worker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAP),
            r -> {
                Thread t = new Thread(r, "snapshot-archive");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> log.warn("快照归档队列已满（{}），丢弃本条", QUEUE_CAP));

    /** 已归档事件 id 去重（内存级；重启后由目标文件已存在实现幂等）。 */
    private final java.util.Set<Long> done = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SnapshotArchiveService(AppSettingsStore settingsStore, EventStore events) {
        this.settingsStore = settingsStore;
        this.events = events;
        events.addRefineListener(this::onRefined);
    }

    /** AI 精化回调（EventStore 锁内调用）：只做入队，复制在工作线程执行。 */
    private void onRefined(AlarmEvent e) {
        if (e == null || !ARCHIVED_TYPES.contains(e.type()) || e.snapshot() == null || e.snapshot().isBlank()) {
            return;
        }
        if (!done.add(e.id())) {
            return;
        }
        try {
            worker.execute(() -> archive(e));
        } catch (RejectedExecutionException ex) {
            done.remove(e.id());
        }
    }

    /** 工作线程：读当前设置 → 复制快照到归档目录。任何异常吞掉只记日志。 */
    private void archive(AlarmEvent e) {
        try {
            var cfg = settingsStore.snapshotArchive();
            if (!Boolean.TRUE.equals(cfg.get("enabled"))) {
                done.remove(e.id()); // 用户稍后开启后，同会话内同 id 事件不复活（事件不重放），此行只为对称清理
                return;
            }
            String base = String.valueOf(cfg.get("dir"));
            if (base.isBlank()) {
                return;
            }
            Path target = expectedArchivePath(Path.of(base), e);
            if (target == null) {
                return;
            }
            if (Files.exists(target)) {
                return; // 幂等：合并重复 / 重启重放
            }
            Path src = events.snapshotPath(e.snapshot());
            if (src == null || !Files.isRegularFile(src)) {
                log.warn("快照归档跳过 id={}：源快照不存在", e.id());
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("快照已归档 id={} → {}", e.id(), target);
        } catch (IOException ex) {
            log.warn("快照归档失败 id={}：{}", e.id(), ex.toString());
        } catch (Exception ex) {
            log.warn("快照归档异常 id={}：{}", e.id(), ex.toString());
        }
    }

    /** 文件系统/共享目录兼容的目录名：非法字符与首尾空白替换，空回落 did，再空回落 "unknown"。 */
    public static String deviceDirName(String deviceName, String did) {
        String s = deviceName == null ? "" : deviceName.trim();
        if (s.isEmpty()) {
            s = did == null ? "" : did.trim();
        }
        if (s.isEmpty()) {
            return "unknown";
        }
        return s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    /**
     * 由事件确定性推导归档文件绝对路径（不发起 IO 的纯计算）：
     * {@code base/yyyy-MM-dd/设备名/HHmmss-{id}-{type}.jpg}。base 为空返回 null。
     */
    public static Path expectedArchivePath(Path base, AlarmEvent e) {
        if (base == null || base.toString().isBlank() || e == null) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        Instant instant = Instant.ofEpochMilli(e.createdAt());
        String day = DAY_DIR.format(LocalDate.ofInstant(instant, zone));
        String time = FILE_TIME.format(instant.atZone(zone));
        String name = time + "-" + e.id() + "-" + e.type() + ".jpg";
        return base.resolve(day).resolve(deviceDirName(e.deviceName(), e.did())).resolve(name);
    }

    /**
     * 按事件 id 反查归档文件（「在文件管理中打开」用）：归档开启、事件类型命中且目标文件
     * 已存在时返回路径，否则 empty。
     */
    public Optional<Path> archivedPathOf(long eventId) {
        var cfg = settingsStore.snapshotArchive();
        if (!Boolean.TRUE.equals(cfg.get("enabled"))) {
            return Optional.empty();
        }
        String base = String.valueOf(cfg.get("dir"));
        if (base.isBlank()) {
            return Optional.empty();
        }
        return events.find(eventId)
                .filter(e -> ARCHIVED_TYPES.contains(e.type()))
                .map(e -> expectedArchivePath(Path.of(base), e))
                .filter(p -> p != null && Files.isRegularFile(p));
    }

    @PreDestroy
    void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    /** 供设置页展示的说明文案（与前端解耦，单一出处）。 */
    public static List<String> describeLayout() {
        return List.of("归档目录/yyyy-MM-dd/摄像头名/HHmmss-事件id-类型.jpg");
    }
}
