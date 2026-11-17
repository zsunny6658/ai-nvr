package com.invr.web.api;

import com.invr.store.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 事件 API 的 HTTP 层：筛选/分页、统计、标记处理、删除、快照字节与 404。 */
class EventsWebTest {

    private static final byte[] JPEG = "FAKE-JPEG".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private EventStore store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new EventStore(dir);
        mvc = MockMvcBuilders.standaloneSetup(new EventsController(store))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listSupportsFilteringAndNewestFirst() throws Exception {
        store.add("d1", "客厅", "motion", "info", "a", 0.2, JPEG, List.of());
        store.add("d2", "门口", "motion", "info", "b", 0.3, null, List.of());
        store.add("d1", "客厅", "person", "warning", "c", 0.9, null, List.of());

        mvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[0].id").value(3))
                .andExpect(jsonPath("$.list[0].typeName").value("AI 人形检测"));

        mvc.perform(get("/api/events").param("did", "d1").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list.length()").value(1));
    }

    @Test
    void statsReflectsHandledState() throws Exception {
        store.add("d1", "客厅", "motion", "info", "a", 0.2, null, List.of());
        store.add("d1", "客厅", "motion", "info", "b", 0.3, null, List.of());
        mvc.perform(get("/api/events/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.unhandled").value(2));

        mvc.perform(put("/api/events/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"handled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handled").value(true));
        mvc.perform(get("/api/events/stats"))
                .andExpect(jsonPath("$.unhandled").value(1));
    }

    @Test
    void snapshotServesJpegWithNoStoreAndMissingReturns404() throws Exception {
        store.add("d1", "客厅", "motion", "info", "a", 0.2, JPEG, List.of());
        store.add("d1", "客厅", "motion", "info", "b", 0.3, null, List.of());

        mvc.perform(get("/api/events/1/snapshot"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", "no-store, no-cache, must-revalidate"))
                .andExpect(content().bytes(JPEG));
        mvc.perform(get("/api/events/2/snapshot")).andExpect(status().isNotFound());
        mvc.perform(get("/api/events/99/snapshot")).andExpect(status().isNotFound());
    }

    @Test
    void deleteAndUnknownIdsReturn404() throws Exception {
        store.add("d1", "客厅", "motion", "info", "a", 0.2, null, List.of());
        mvc.perform(delete("/api/events/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
        mvc.perform(delete("/api/events/1")).andExpect(status().isNotFound());
        mvc.perform(put("/api/events/1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"handled\":true}")).andExpect(status().isNotFound());
    }
}
