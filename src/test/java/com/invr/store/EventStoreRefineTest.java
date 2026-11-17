package com.invr.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v2.1.0 AI 事件精化：refine 原位更新 + JSONL 重放恢复。 */
class EventStoreRefineTest {

    @TempDir
    Path tmp;

    @Test
    void refineUpdatesTypeAndDetections() {
        EventStore store = new EventStore(tmp);
        EventStore.AlarmEvent e = store.add("d1", "前门", "motion", "info", "画面变动", 0.8, null, List.of());
        assertTrue(e.detections().isEmpty());

        var refined = store.refine(e.id(), "person", List.of(Map.of(
                "label", "person", "type", "person", "score", 0.87,
                "box", List.of(0.1, 0.2, 0.8, 0.9))));
        assertTrue(refined.isPresent());
        assertEquals("person", refined.get().type());
        assertEquals(1, refined.get().detections().size());

        // 重放恢复：新实例应还原 refine 后的类型与 detections
        EventStore reloaded = new EventStore(tmp);
        var list = reloaded.list(new EventStore.Filter(null, null, null, null, null, null, 10, 0)).items();
        assertEquals(1, list.size());
        assertEquals("person", list.get(0).type());
        assertEquals(0.87, ((Number) list.get(0).detections().get(0).get("score")).doubleValue(), 1e-6);
    }

    @Test
    void refineOnlyAppliesToMotionEvents() {
        EventStore store = new EventStore(tmp);
        EventStore.AlarmEvent e = store.add("d1", "前门", "offline", "warn", "设备离线", 0.0, null, List.of());
        assertTrue(store.refine(e.id(), "person", List.of()).isEmpty());
        // 不存在的事件同样返回空
        assertTrue(store.refine(99999, "person", List.of()).isEmpty());
    }

    @Test
    void extraListenerReceivesAdd() {
        EventStore store = new EventStore(tmp);
        final EventStore.AlarmEvent[] got = new EventStore.AlarmEvent[1];
        store.addListener(ev -> got[0] = ev);
        store.add("d1", "前门", "motion", "info", "画面变动", 0.5, null, List.of());
        assertEquals("motion", got[0].type());
    }
}
