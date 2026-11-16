package com.invr.store;

import com.invr.codec.FrameQuality;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 告警事件存储：append-only JSONL + 内存索引 + 独立 JPEG 快照文件。
 *
 * <p>刻意不引入数据库：i-nvr 其它状态均为 {@code .invr/} 下的轻量文件，事件量级（默认上限
 * 2000 条）用 JSONL 重放完全够用，也不会让 fpk 变大或拖慢启动。每次写操作追加一行
 * {@code {"op": ...}}，启动按顺序重放恢复 {@code add / handled / delete} 三种意图；行数超过
 * 上限两倍时压缩重写一次（临时文件 + 原子 rename），避免日志无限膨胀。</p>
 *
 * <p>快照与事件分离存放，删除事件时同步清理；快照路径在库内只存相对名，读取前做
 * {@code normalize().startsWith(root)} 校验，防止手工篡改文件造成目录穿越。</p>
 */
public class EventStore {

    private static final Logger log = LoggerFactory.getLogger(EventStore.class);

    private static final String EVENTS_DIR = "events";
    private static final String LOG_FILE = "events.jsonl";
    private static final String SNAPSHOT_DIR = "snapshots";
    /**
     * 有效快照的最小体积：640x360 纯色灰屏 JPEG 约 1.6KB，真实画面约 37KB。
     * 首页卡片只展示有画面的事件，历史上 v1.2.12 之前产生的灰屏误报快照在此被剔除。
     */
    private static final long MIN_VALID_SNAPSHOT_BYTES = 2000L;
    /** 首页聚合最多回溯扫描的事件条数，避免事件库较大时逐个 stat 全表文件。 */
    private static final int RECENT_SCAN_LIMIT = 400;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** 一条告警事件。{@code snapshot} 为相对快照目录的文件名，空串表示无快照。 */
    public record AlarmEvent(
            long id,
            String did,
            String deviceName,
            String type,
            String level,
            String title,
            double score,
            String snapshot,
            long createdAt,
            boolean handled,
            Long handledAt,
            List<Map<String, Object>> detections,
            /** v1.2.21 持续变动合并计数：0 表示首条，N 表示其后合并进了 N 次重复触发。 */
            int repeat) {
    }

    /** 列表筛选条件；{@code date} 为本地时区 yyyy-MM-dd，fromMs/toMs 为 epoch 毫秒。 */
    public record Filter(String did, String type, Boolean handled, String date,
                         Long fromMs, Long toMs, int limit, int offset) {
        public static Filter of(String did, String type, Boolean handled, String date,
                                Long fromMs, Long toMs, int limit, int offset) {
            return new Filter(blankToNull(did), blankToNull(type), handled, blankToNull(date),
                    fromMs, toMs, Math.max(1, Math.min(200, limit)), Math.max(0, offset));
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s.trim();
        }
    }

    public record Page(List<AlarmEvent> items, int total) {
    }

    private final Path root;
    private final Path logFile;
    private final Path snapshotsDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LinkedHashMap<Long, AlarmEvent> events = new LinkedHashMap<>();
    /** 快照质量判定缓存：key = 绝对路径 + ":" + 文件体积，快照写入后不再变化。 */
    private final Map<String, Boolean> brokenCache = new java.util.HashMap<>();
    private long nextId = 1;
    private long lineCount = 0;
    /**
     * 事件新增监听（v1.2.19 通知推送）：在 {@link #add} 内同步回调。
     * 监听方必须自行异步化并吞掉异常，且不得回调本类的写方法（当前持锁）。
     */
    private volatile Consumer<AlarmEvent> addListener;

    public EventStore() {
        this(DataDir.get());
    }

    public EventStore(Path dataDir) {
        this.root = dataDir.resolve(EVENTS_DIR);
        this.logFile = root.resolve(LOG_FILE);
        this.snapshotsDir = root.resolve(SNAPSHOT_DIR);
        load();
    }

    // ===================== 写 =====================

    /**
     * 新增事件并落盘快照。快照写失败不阻断事件记录（仅该条没有截图）。
     */
    public synchronized AlarmEvent add(String did, String deviceName, String type, String level,
                                       String title, double score, byte[] jpeg,
                                       List<Map<String, Object>> detections) {
        long now = System.currentTimeMillis();
        long id = nextId++;
        String rel = "";
        if (jpeg != null && jpeg.length > 0) {
            rel = dayOf(now) + "/" + id + ".jpg";
            try {
                Path target = snapshotFile(rel);
                if (target == null) {
                    rel = "";
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, jpeg);
                }
            } catch (IOException e) {
                log.warn("事件快照写入失败 id={}: {}", id, e.toString());
                rel = "";
            }
        }
        AlarmEvent event = new AlarmEvent(id, nullToEmpty(did), nullToEmpty(deviceName),
                nullToEmpty(type), nullToEmpty(level), nullToEmpty(title), score, rel, now,
                false, null, detections == null ? List.of() : List.copyOf(detections), 0);
        events.put(id, event);
        append(Map.of("op", "add", "event", event));
        Consumer<AlarmEvent> l = addListener;
        if (l != null) {
            try {
                l.accept(event);
            } catch (RuntimeException e) {
                log.warn("事件监听回调失败 id={}: {}", id, e.toString());
            }
        }
        return event;
    }

    /** 注册新增事件监听（重复调用覆盖前者）；传 null 取消。供通知推送等出向通道使用。 */
    public void setAddListener(Consumer<AlarmEvent> listener) {
        this.addListener = listener;
    }

    /**
     * v1.2.21 持续变动事件合并：同设备 motion 事件的最近一条若在 {@code mergeWindowMs} 内，
     * 不再新增一条（刷屏），而是原位更新该条——repeat 计数 +1、时间戳推进到本次、
     * 快照替换为最新帧（同日同 id 覆盖）、分数取历史最高。窗口外或无同设备事件时走普通
     * {@link #add}（repeat 从 0 开始）。
     *
     * <p>合并路径<strong>不触发 addListener</strong>：合并的目的就是抑制重复通知。</p>
     *
     * @return 落库后的事件（合并时 id 与首条相同）
     */
    public synchronized AlarmEvent addOrMergeMotion(String did, String deviceName, String title,
                                                    double score, byte[] jpeg, long mergeWindowMs) {
        long now = System.currentTimeMillis();
        AlarmEvent last = null;
        for (AlarmEvent e : events.values()) { // LinkedHashMap 按插入序，取最后一条同设备 motion
            if (did != null && did.equals(e.did()) && "motion".equals(e.type())) {
                last = e;
            }
        }
        if (last != null && now - last.createdAt() <= mergeWindowMs) {
            String rel = last.snapshot();
            if (jpeg != null && jpeg.length > 0) {
                String candidate = dayOf(now) + "/" + last.id() + ".jpg";
                try {
                    Path target = snapshotFile(candidate);
                    if (target != null) {
                        Files.createDirectories(target.getParent());
                        Files.write(target, jpeg);
                        rel = candidate;
                    }
                } catch (IOException e) {
                    log.warn("合并事件快照更新失败 id={}: {}", last.id(), e.toString());
                }
            }
            AlarmEvent merged = new AlarmEvent(last.id(), last.did(), last.deviceName(),
                    last.type(), last.level(), last.title(), Math.max(last.score(), score),
                    rel, now, last.handled(), last.handledAt(), last.detections(),
                    last.repeat() + 1);
            events.put(merged.id(), merged);
            append(Map.of("op", "add", "event", merged)); // 重放时同 id 后者覆盖
            return merged;
        }
        return add(did, deviceName, "motion", "info", title, score, jpeg, List.of());
    }

    /** 标记已处理 / 取消处理；返回更新后的事件，不存在返回空。 */
    public synchronized Optional<AlarmEvent> markHandled(long id, boolean handled) {
        AlarmEvent old = events.get(id);
        if (old == null) {
            return Optional.empty();
        }
        long at = handled ? System.currentTimeMillis() : 0L;
        AlarmEvent updated = new AlarmEvent(old.id(), old.did(), old.deviceName(), old.type(),
                old.level(), old.title(), old.score(), old.snapshot(), old.createdAt(),
                handled, handled ? at : null, old.detections(), old.repeat());
        events.put(id, updated);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("op", "handled");
        line.put("id", id);
        line.put("handled", handled);
        line.put("at", at);
        append(line);
        return Optional.of(updated);
    }

    /** 删除事件及其快照文件。 */
    public synchronized boolean delete(long id) {
        AlarmEvent e = events.remove(id);
        if (e == null) {
            return false;
        }
        deleteSnapshot(e.snapshot());
        append(Map.of("op", "delete", "id", id));
        return true;
    }

    /**
     * v1.2.15 批量删除：内存索引一次更新 + 日志单次原子重写。
     *
     * <p>旧实现逐个调用 {@link #delete(long)}，N 条事件要开关 N 次 JSONL、追加 N 行删除意图，
     * 事件量大时在 NAS 慢盘上会长时间占用 EventStore 锁并让日志膨胀；重写后日志只保留
     * 仍在的事件，重启重放结果一致。快照逐个容错清理，任一异常不影响其余事件。</p>
     */
    public synchronized int deleteBatch(Collection<Long> ids) {
        List<AlarmEvent> doomed = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            AlarmEvent e = events.remove(id);
            if (e != null) {
                doomed.add(e);
            }
        }
        if (doomed.isEmpty()) {
            return 0;
        }
        commitRemoval(doomed);
        return doomed.size();
    }

    /** v1.2.15 批量标记已处理：一次重写日志，避免 N 次 append。 */
    public synchronized int markHandledBatch(Collection<Long> ids, boolean handled) {
        long at = System.currentTimeMillis();
        List<Long> touched = new ArrayList<>();
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            AlarmEvent old = events.get(id);
            if (old == null) {
                continue;
            }
            events.put(id, new AlarmEvent(old.id(), old.did(), old.deviceName(), old.type(),
                    old.level(), old.title(), old.score(), old.snapshot(), old.createdAt(),
                    handled, handled ? at : null, old.detections(), old.repeat()));
            touched.add(id);
        }
        if (touched.isEmpty()) {
            return 0;
        }
        if (!rewriteLog()) {
            for (Long id : touched) {
                Map<String, Object> line = new LinkedHashMap<>();
                line.put("op", "handled");
                line.put("id", id);
                line.put("handled", handled);
                line.put("at", handled ? at : 0L);
                append(line);
            }
        }
        return touched.size();
    }

    /**
     * v1.2.15 事件清空：条件全空表示清空全部，否则只清与筛选条件匹配的事件。
     * 全部条件复用于列表查询的 {@link #matches(AlarmEvent, Filter)}，保证「当前筛选」与
     * 「清空当前筛选」口径一致。
     */
    public synchronized int clearMatching(Filter f) {
        boolean all = f == null || (f.did() == null && f.type() == null && f.handled() == null
                && f.date() == null && f.fromMs() == null && f.toMs() == null);
        List<AlarmEvent> doomed = new ArrayList<>();
        for (AlarmEvent e : events.values()) {
            if (all || matches(e, f)) {
                doomed.add(e);
            }
        }
        if (doomed.isEmpty()) {
            return 0;
        }
        for (AlarmEvent e : doomed) {
            events.remove(e.id());
        }
        commitRemoval(doomed);
        return doomed.size();
    }

    /** 内存索引已移除后统一提交：优先单次原子重写日志，失败退回逐条追加删除意图。 */
    private void commitRemoval(List<AlarmEvent> doomed) {
        if (!rewriteLog()) {
            for (AlarmEvent e : doomed) {
                append(Map.of("op", "delete", "id", e.id()));
            }
        }
        for (AlarmEvent e : doomed) {
            deleteSnapshot(e.snapshot());
        }
    }

    /**
     * v1.2.16：清理历史上「残缺帧 / 纯色灰屏」造成的糊屏事件，供用户在事件页一键调用。
     * 只删除快照被判定为无效的事件（含快照文件本身），保留所有正常画面事件。
     *
     * @return 实际删除条数
     */
    public synchronized int purgeBrokenSnapshots() {
        List<AlarmEvent> doomed = new ArrayList<>();
        for (AlarmEvent e : events.values()) {
            Path p = snapshotFile(e.snapshot());
            if (p != null && Files.isRegularFile(p) && isBrokenSnapshot(p)) {
                doomed.add(e);
            }
        }
        if (doomed.isEmpty()) {
            return 0;
        }
        for (AlarmEvent e : doomed) {
            events.remove(e.id());
        }
        commitRemoval(doomed);
        return doomed.size();
    }

    /** 判定快照是否为残缺帧 / 纯色灰屏；结果按路径+体积缓存，避免首页轮询反复解码。 */
    private boolean isBrokenSnapshot(Path p) {
        try {
            long size = Files.size(p);
            String key = p + ":" + size;
            Boolean cached = brokenCache.get(key);
            if (cached != null) {
                return cached;
            }
            boolean broken = size < MIN_VALID_SNAPSHOT_BYTES
                    || FrameQuality.isBroken(Files.readAllBytes(p));
            if (brokenCache.size() > 4000) {
                brokenCache.clear();
            }
            brokenCache.put(key, broken);
            return broken;
        } catch (Exception e) {
            log.debug("事件快照质量判定失败，按有效处理：{}", e.toString());
            return false;
        }
    }

    /**
     * 按保留天数与条数上限清理最老的事件。由巡检循环周期调用，无需额外定时线程。
     *
     * @return 实际删除条数
     */
    public synchronized int purge(int maxEvents, int retentionDays) {
        long cutoff = retentionDays > 0
                ? System.currentTimeMillis() - retentionDays * 86_400_000L
                : Long.MIN_VALUE;
        java.util.Set<Long> doomed = new java.util.LinkedHashSet<>();
        for (AlarmEvent e : events.values()) {
            if (e.createdAt() < cutoff) {
                doomed.add(e.id());
            }
        }
        int keepLimit = Math.max(100, maxEvents);
        if (events.size() - doomed.size() > keepLimit) {
            List<Long> byAge = new ArrayList<>(events.keySet());
            for (Long id : byAge) {
                if (events.size() - doomed.size() <= keepLimit) {
                    break;
                }
                if (!doomed.contains(id)) {
                    AlarmEvent e = events.get(id);
                    if (e != null && e.createdAt() >= cutoff) {
                        doomed.add(id);
                    }
                }
            }
        }
        List<AlarmEvent> removed = new ArrayList<>();
        for (Long id : doomed) {
            AlarmEvent e = events.remove(id);
            if (e != null) {
                removed.add(e);
            }
        }
        // v1.2.15：改为单次重写，顺带完成日志压缩，避免逐条追加让 JSONL 持续膨胀
        if (!removed.isEmpty()) {
            commitRemoval(removed);
        } else if (lineCount > (long) keepLimit * 2) {
            // 没有删除但历史「标记已处理」行已堆积时，仍按原策略压缩一次
            rewriteLog();
        }
        return removed.size();
    }

    // ===================== 读 =====================

    /** 倒序（最新在前）分页查询。 */
    public synchronized Page list(Filter f) {
        List<AlarmEvent> matched = new ArrayList<>();
        for (AlarmEvent e : events.values()) {
            if (matches(e, f)) {
                matched.add(e);
            }
        }
        matched.sort(Comparator.comparingLong(AlarmEvent::id).reversed());
        int total = matched.size();
        if (f.offset() >= total || f.limit() <= 0) {
            return new Page(List.of(), total);
        }
        int end = Math.min(total, f.offset() + f.limit());
        return new Page(List.copyOf(matched.subList(f.offset(), end)), total);
    }

    /** 首页概览所需聚合：总数 / 今日 / 未处理 / 按类型 / 按设备。 */
    public synchronized Map<String, Object> stats() {
        String today = dateOf(System.currentTimeMillis());
        int todayCount = 0;
        int unhandled = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> byDid = new LinkedHashMap<>();
        for (AlarmEvent e : events.values()) {
            if (today.equals(dateOf(e.createdAt()))) todayCount++;
            if (!e.handled()) unhandled++;
            byType.merge(e.type(), 1, Integer::sum);
            byDid.merge(e.did(), 1, Integer::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", events.size());
        m.put("today", todayCount);
        m.put("unhandled", unhandled);
        m.put("byType", byType);
        m.put("byDid", byDid);
        return m;
    }

    /**
     * 首页摄像头卡片用：按设备取最近若干条「有有效画面快照」的事件（各自倒序）。
     *
     * <p>跳过无快照、快照文件已清理、文件体积过小的纯色灰屏帧，以及 v1.2.16 起识别的
     * 「大片纯灰 + 局部残差」残缺帧；某台设备近期全是坏帧时会继续向前回溯，直到凑够条数
     * 或达到 {@link #RECENT_SCAN_LIMIT}。质量判定结果按路径+体积缓存，30 秒一次的首页
     * 轮询只有首次需要解码 JPEG。</p>
     */
    public synchronized Map<String, List<AlarmEvent>> recentWithSnapshots(int perDevice) {
        int per = Math.max(1, Math.min(6, perDevice));
        List<AlarmEvent> desc = new ArrayList<>(events.values());
        desc.sort(Comparator.comparingLong(AlarmEvent::id).reversed());
        Map<String, List<AlarmEvent>> out = new LinkedHashMap<>();
        int scanned = 0;
        for (AlarmEvent e : desc) {
            if (++scanned > RECENT_SCAN_LIMIT) {
                break;
            }
            if (e.snapshot() == null || e.snapshot().isEmpty()) {
                continue;
            }
            List<AlarmEvent> bucket = out.get(e.did());
            if (bucket != null && bucket.size() >= per) {
                continue;
            }
            Path p = snapshotFile(e.snapshot());
            if (p == null || !Files.isRegularFile(p)) {
                continue;
            }
            if (isBrokenSnapshot(p)) {
                continue;
            }
            if (bucket == null) {
                bucket = new ArrayList<>();
                out.put(e.did(), bucket);
            }
            bucket.add(e);
        }
        return out;
    }

    public synchronized Optional<AlarmEvent> find(long id) {
        return Optional.ofNullable(events.get(id));
    }

    public synchronized int size() {
        return events.size();
    }

    /** 读取事件快照；无快照 / 文件缺失 / 路径非法时返回空。 */
    public byte[] snapshot(long id) throws IOException {
        AlarmEvent e;
        synchronized (this) {
            e = events.get(id);
        }
        if (e == null) {
            return null;
        }
        Path p = snapshotFile(e.snapshot());
        if (p == null || !Files.isRegularFile(p)) {
            return null;
        }
        return Files.readAllBytes(p);
    }

    /** 供控制器生成展示用的相对 URL 片段（不含网关前缀）。 */
    public static String snapshotUrl(AlarmEvent e) {
        return e.snapshot().isEmpty() ? "" : "/api/events/" + e.id() + "/snapshot";
    }

    // ===================== 内部 =====================

    private void load() {
        if (!Files.isRegularFile(logFile)) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                lineCount++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> obj = mapper.readValue(line, MAP_TYPE);
                    String op = String.valueOf(obj.getOrDefault("op", ""));
                    long id = ((Number) obj.getOrDefault("id", 0)).longValue();
                    switch (op) {
                        case "add" -> {
                            AlarmEvent e = mapper.convertValue(obj.get("event"), AlarmEvent.class);
                            if (e != null) {
                                events.put(e.id(), e);
                                nextId = Math.max(nextId, e.id() + 1);
                            }
                        }
                        case "handled" -> {
                            AlarmEvent old = events.get(id);
                            if (old != null) {
                                boolean h = Boolean.TRUE.equals(obj.get("handled"));
                                long at = obj.get("at") instanceof Number n ? n.longValue() : 0L;
                                events.put(id, new AlarmEvent(old.id(), old.did(), old.deviceName(),
                                        old.type(), old.level(), old.title(), old.score(),
                                        old.snapshot(), old.createdAt(), h,
                                        h && at > 0 ? at : null, old.detections(), old.repeat()));
                            }
                        }
                        case "delete" -> events.remove(id);
                        default -> log.debug("忽略未知事件日志操作：{}", op);
                    }
                } catch (Exception e) {
                    log.warn("事件日志跳过损坏行：{}", e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("事件日志读取失败，按空库启动：{}", e.toString());
        }
    }

    private void append(Map<String, Object> line) {
        try {
            Files.createDirectories(root);
            String text = mapper.writeValueAsString(line) + "\n";
            Files.writeString(logFile, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lineCount++;
        } catch (IOException e) {
            // 内存索引仍然有效，本轮请求不失败；下次进程重启可能丢失这一行
            log.error("事件日志追加失败：{}", e.toString());
        }
    }

    /**
     * 用当前内存索引原子重写日志（临时文件 + rename），去掉已被删除/覆盖的历史行。
     * 全部清空时写出一个空文件即可，不需要逐条追加删除意图。
     *
     * @return 是否重写成功；失败时保留原文件，调用方需降级为追加删除意图
     */
    private boolean rewriteLog() {
        Path tmp = logFile.resolveSibling(LOG_FILE + ".tmp");
        try {
            Files.createDirectories(root);
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (AlarmEvent e : events.values()) {
                    w.write(mapper.writeValueAsString(Map.of("op", "add", "event", e)));
                    w.newLine();
                }
            }
            try {
                Files.move(tmp, logFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupported) {
                Files.move(tmp, logFile, StandardCopyOption.REPLACE_EXISTING);
            }
            lineCount = events.size();
            return true;
        } catch (Exception e) {
            log.warn("事件日志重写失败，保留原文件：{}", e.toString());
            return false;
        }
    }

    /** 与分页查询、条件清空共用的匹配口径。 */
    private static boolean matches(AlarmEvent e, Filter f) {
        if (f == null) {
            return true;
        }
        if (f.did() != null && !f.did().equals(e.did())) return false;
        if (f.type() != null && !f.type().equals(e.type())) return false;
        if (f.handled() != null && f.handled() != e.handled()) return false;
        if (f.date() != null && !f.date().equals(dateOf(e.createdAt()))) return false;
        if (f.fromMs() != null && e.createdAt() < f.fromMs()) return false;
        if (f.toMs() != null && e.createdAt() > f.toMs()) return false;
        return true;
    }

    private Path snapshotFile(String rel) {
        if (rel == null || rel.isBlank()) {
            return null;
        }
        Path p = snapshotsDir.resolve(rel).normalize();
        return p.startsWith(snapshotsDir) ? p : null;
    }

    private void deleteSnapshot(String rel) {
        try {
            Path p = snapshotFile(rel);
            if (p == null) {
                return;
            }
            Files.deleteIfExists(p);
        } catch (Exception e) {
            // 批量清空时单条异常路径不能中断整批操作（路径篡改 / 权限 / 文件占用）
            log.debug("事件快照删除失败 rel={}：{}", rel, e.toString());
        }
    }

    private static String dayOf(long epochMs) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(DAY);
    }

    private static String dateOf(long epochMs) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(DATE);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
