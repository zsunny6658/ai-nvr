package com.invr.web.api;

import com.invr.store.EventStore;
import com.invr.store.EventStore.AlarmEvent;
import com.invr.store.EventStore.Filter;
import com.invr.store.EventStore.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 告警事件查询与处理接口。位于 {@code /api/**}，自动受访问密码门禁保护；事件快照供
 * {@code <img>} 使用时可走 {@code ?__invr_t=<token>}（与 HLS 一致的令牌通道）。
 *
 * <p>本阶段只提供 API 与服务端存储，不含界面；运动侦测默认关闭，需通过
 * {@code PUT /api/motion/config} 打开。</p>
 */
@RestController
@RequestMapping("/api")
public class EventsController {

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "motion", "运动侦测",
            "record", "自动录像",
            "person", "人形检测",
            "vehicle", "车辆检测",
            "offline", "设备离线",
            "alarm", "设备告警");

    /** 事件页批量操作一次最多处理 200 条，避免一次请求写爆 JSONL。 */
    private static final int MAX_BATCH = 200;

    private final EventStore store;

    public EventsController(EventStore store) {
        this.store = store;
    }

    /** 倒序分页查询，支持设备 / 类型 / 已处理 / 日期 / 时间区间筛选。 */
    @GetMapping("/events")
    public Map<String, Object> list(@RequestParam(name = "did", required = false) String did,
                                    @RequestParam(name = "type", required = false) String type,
                                    @RequestParam(name = "handled", required = false) Boolean handled,
                                    @RequestParam(name = "date", required = false) String date,
                                    @RequestParam(name = "fromMs", required = false) Long fromMs,
                                    @RequestParam(name = "toMs", required = false) Long toMs,
                                    @RequestParam(name = "limit", defaultValue = "20") int limit,
                                    @RequestParam(name = "offset", defaultValue = "0") int offset) {
        Page page = store.list(Filter.of(did, type, handled, date, fromMs, toMs, limit, offset));
        List<Map<String, Object>> items = new ArrayList<>();
        for (AlarmEvent e : page.items()) {
            items.add(present(e));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("list", items);
        m.put("total", page.total());
        m.put("limit", limit);
        m.put("offset", offset);
        return m;
    }

    /** 首页概览聚合。 */
    @GetMapping("/events/stats")
    public Map<String, Object> stats() {
        return store.stats();
    }

    public record HandledRequest(Boolean handled) {
    }

    /** 标记已处理 / 取消处理。 */
    @PutMapping("/events/{id}")
    public Map<String, Object> markHandled(@PathVariable long id,
                                           @RequestBody(required = false) HandledRequest req) {
        boolean handled = req == null || req.handled() == null || req.handled();
        AlarmEvent e = store.markHandled(id, handled)
                .orElseThrow(() -> ApiException.notFound("事件不存在：id=" + id));
        return present(e);
    }

    /** 删除单条事件（含其快照文件）。 */
    @DeleteMapping("/events/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        if (!store.delete(id)) {
            throw ApiException.notFound("事件不存在：id=" + id);
        }
        return Map.of("deleted", id);
    }

    public record IdsRequest(List<Long> ids) {
    }

    /** 事件页批量标记已处理 / 取消处理。 */
    @PutMapping("/events/batch/handled")
    public Map<String, Object> markHandledBatch(
            @RequestParam(name = "handled", defaultValue = "true") boolean handled,
            @RequestBody(required = false) IdsRequest req) {
        List<Long> ids = batchIds(req);
        int n = 0;
        for (Long id : ids) {
            if (store.markHandled(id, handled).isPresent()) {
                n++;
            }
        }
        return Map.of("updated", n, "handled", handled);
    }

    /** 事件页批量删除（含各自快照）。 */
    @PostMapping("/events/batch/delete")
    public Map<String, Object> deleteBatch(@RequestBody(required = false) IdsRequest req) {
        List<Long> ids = batchIds(req);
        int n = 0;
        for (Long id : ids) {
            if (store.delete(id)) {
                n++;
            }
        }
        return Map.of("deleted", n);
    }

    private static List<Long> batchIds(IdsRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw ApiException.badRequest("请先选择事件");
        }
        List<Long> out = new ArrayList<>();
        for (Long id : req.ids()) {
            if (id != null && !out.contains(id)) {
                out.add(id);
            }
        }
        if (out.size() > MAX_BATCH) {
            throw ApiException.badRequest("单次最多处理 " + MAX_BATCH + " 条事件，请分批操作");
        }
        return out;
    }

    /** 事件快照 JPEG；无快照时返回 404。 */
    @GetMapping("/events/{id}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable long id) throws IOException {
        byte[] jpeg = store.snapshot(id);
        if (jpeg == null) {
            throw ApiException.notFound("该事件没有可用快照：id=" + id);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .contentLength(jpeg.length)
                .body(jpeg);
    }

    private static Map<String, Object> present(AlarmEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("did", e.did());
        m.put("deviceName", e.deviceName());
        m.put("type", e.type());
        m.put("typeName", TYPE_NAMES.getOrDefault(e.type(), e.type()));
        m.put("level", e.level());
        m.put("title", e.title());
        m.put("score", e.score());
        m.put("createdAt", e.createdAt());
        m.put("handled", e.handled());
        m.put("handledAt", e.handledAt());
        m.put("detections", e.detections());
        m.put("snapshotUrl", EventStore.snapshotUrl(e));
        return m;
    }
}
