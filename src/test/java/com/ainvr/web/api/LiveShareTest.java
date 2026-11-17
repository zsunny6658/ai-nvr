package com.ainvr.web.api;

import com.ainvr.model.CameraDevice;
import com.ainvr.model.ShareBoard;
import com.ainvr.store.ShareBoardStore;
import com.ainvr.web.service.BoardLayouts;
import com.ainvr.web.service.DeviceCatalog;
import com.ainvr.web.service.ShareBoardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1.2.3「外网可播链接」：单画面布局、临时看板的创建 / 复用 / 撤销 / 过期不复用，
 * 以及设备不存在时的 404。全部走 standaloneSetup，不启动 Spring 上下文（门禁全局覆盖）。
 */
class LiveShareTest {

    private static final String DID = "1072813513";

    @TempDir
    Path dir;

    private MockMvc mvc;
    private ShareBoardService boards;
    private ShareBoardStore store;

    @BeforeEach
    void setUp() {
        DeviceCatalog catalog = Mockito.mock(DeviceCatalog.class);
        CameraDevice cam = new CameraDevice(DID, "客厅摄像机", "isa.camera.hlc8",
                "", "192.168.31.68", true);
        Mockito.when(catalog.find(DID)).thenReturn(Optional.of(cam));
        store = new ShareBoardStore(dir);
        boards = new ShareBoardService(store, catalog,
                Mockito.mock(ApplicationEventPublisher.class));
        mvc = MockMvcBuilders.standaloneSetup(new LiveShareController(boards, catalog))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void singleLayoutHasOneSlot() {
        assertEquals(Optional.of(1), ShareBoardService.slotCount(ShareBoard.LAYOUT_SINGLE));
        assertEquals(1, BoardLayouts.slots(ShareBoard.LAYOUT_SINGLE).size());
    }

    @Test
    void createReturnsSameOriginLinkAnd24hExpiry() throws Exception {
        long before = System.currentTimeMillis();
        mvc.perform(post("/api/live-share/" + DID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.reused").value(false))
                .andExpect(jsonPath("$.path").value(org.hamcrest.Matchers.startsWith("share/")))
                .andExpect(jsonPath("$.token").exists());

        ShareBoard b = boards.list().get(0);
        assertEquals(ShareBoard.LAYOUT_SINGLE, b.layout);
        assertEquals(List.of(DID), b.cameras);
        assertTrue(b.expireAt >= before + LiveShareController.TTL_MS - 2000);
        assertTrue(b.expireAt <= System.currentTimeMillis() + LiveShareController.TTL_MS + 2000);
        assertTrue(b.enabled);
    }

    @Test
    void secondCreateReusesSameToken() throws Exception {
        String first = mvc.perform(post("/api/live-share/" + DID))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/live-share/" + DID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(true))
                .andExpect(jsonPath("$.token").value(jsonToken(first)));
        assertEquals(1, boards.list().size());
    }

    @Test
    void revokeMakesLinkInactive() throws Exception {
        String body = mvc.perform(post("/api/live-share/" + DID))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = jsonString(body, "id");
        mvc.perform(delete("/api/live-share/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(id));
        mvc.perform(get("/api/live-share/" + DID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertEquals(0, boards.list().size());
    }

    @Test
    void nearlyExpiredLinkIsNotReused() throws Exception {
        mvc.perform(post("/api/live-share/" + DID)).andExpect(status().isOk());
        ShareBoard b = boards.list().get(0);
        b.expireAt = System.currentTimeMillis() + 30_000L; // 短于 60s 复用下限
        store.save(b);
        mvc.perform(post("/api/live-share/" + DID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reused").value(false));
        assertEquals(2, boards.list().size());
    }

    @Test
    void unknownDeviceIsRejected() throws Exception {
        mvc.perform(post("/api/live-share/999999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    private static String jsonToken(String json) {
        return jsonString(json, "token");
    }

    /** 仅用于本测试解析简单扁平 JSON 字段，避免引入额外依赖。 */
    private static String jsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        assertTrue(i >= 0, "响应缺少字段 " + key + "：" + json);
        int start = i + needle.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
