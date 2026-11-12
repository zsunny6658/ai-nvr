package com.invr.web.api;

import com.invr.store.EventStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 事件页批量标记 / 批量删除接口的 HTTP 层与参数边界。 */
class EventsBatchWebTest {

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
        for (int i = 0; i < 3; i++) {
            store.add("d1", "客厅", "motion", "info", "event-" + i, 0.2, null, List.of());
        }
        store.add("d1", "客厅", "record", "info", "自动录像", 0, null, List.of());
    }

    @Test
    void batchMarkHandledAndUnhandled() throws Exception {
        mvc.perform(put("/api/events/batch/handled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1,2,2,999]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.handled").value(true));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/events").param("handled", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));

        mvc.perform(put("/api/events/batch/handled").param("handled", "false")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.handled").value(false));
    }

    @Test
    void batchDeleteRemovesEventsAndSnapshots() throws Exception {
        mvc.perform(post("/api/events/batch/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[2,3,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/events/2/snapshot"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsEmptyOrOversizedBatches() throws Exception {
        mvc.perform(put("/api/events/batch/handled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/events/batch/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        StringBuilder big = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) big.append(',');
            big.append(i + 1);
        }
        big.append("]}");
        mvc.perform(post("/api/events/batch/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(big.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recordEventHasChineseTypeName() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/events").param("type", "record"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].typeName").value("自动录像"));
    }
}
