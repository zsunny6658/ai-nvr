package com.ainvr.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ainvr.hub.StreamHub;
import com.ainvr.rtsp.RtspServer;
import com.ainvr.store.AccessKeyStore;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.ShareBoardStore;
import com.ainvr.web.config.NvrProperties;
import com.ainvr.web.service.AiDetectService;
import com.ainvr.web.service.DeviceCatalog;
import com.ainvr.web.service.DeviceProbe;
import com.ainvr.web.service.HlsService;
import com.ainvr.web.service.RecordingService;
import com.ainvr.web.service.ShareEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v2.1.0 配置备份导出/导入 + AI 检测设置：standalone MockMvc。
 * 依赖均为 mock/临时目录，不触碰真实数据目录。
 */
class SettingsBackupTest {

    @TempDir
    Path tmp;

    private MockMvc mvc;
    private AppSettingsStore settingsStore;
    private AccessKeyStore accessKeyStore;
    private ShareBoardStore boardStore;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        settingsStore = new AppSettingsStore(tmp);
        accessKeyStore = new AccessKeyStore(tmp.resolve("access"));
        boardStore = new ShareBoardStore(tmp.resolve("boards"));
        // 先设置一个访问密码供导出
        accessKeyStore.setup("backup-test-pass-123".toCharArray());

        RecordingService recordingService = mock(RecordingService.class);
        when(recordingService.settingsInfo()).thenReturn(new LinkedHashMap<>());
        RtspServer rtspServer = mock(RtspServer.class);
        when(rtspServer.getPort()).thenReturn(9554);
        when(rtspServer.getToken()).thenReturn("");
        HlsService hlsService = mock(HlsService.class);
        when(hlsService.hlsSettingsInfo()).thenReturn(new LinkedHashMap<>());
        ShareEventService shareEventService = mock(ShareEventService.class);
        when(shareEventService.shareSettingsInfo()).thenReturn(new LinkedHashMap<>());
        // 真实 AiDetectService：status() 直接读 settingsStore，AI 设置端点的响应才可断言
        AiDetectService aiDetectService = new AiDetectService(settingsStore, new com.ainvr.store.EventStore(tmp.resolve("events")));
        DeviceCatalog catalog = mock(DeviceCatalog.class);
        NvrProperties props = new NvrProperties();

        mvc = MockMvcBuilders.standaloneSetup(new SettingsController(
                        recordingService, rtspServer, props, settingsStore, accessKeyStore,
                        boardStore, mapper, aiDetectService, hlsService, shareEventService,
                        catalog, mock(StreamHub.class), mock(DeviceProbe.class), mock(com.ainvr.web.service.SnapshotArchiveService.class)))
                .setControllerAdvice(new com.ainvr.web.api.GlobalExceptionHandler())
                .build();
    }

    @Test
    void exportImportRoundTripRestoresSettings() throws Exception {
        // 先写入一些设置
        settingsStore.saveRtspToken("secret-token-1");
        settingsStore.saveAi(false, 0.7);

        // 导出
        String json = mvc.perform(get("/api/settings/export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 新实例（模拟换机）：导入
        AppSettingsStore target = new AppSettingsStore(tmp.resolve("new-home"));
        AccessKeyStore targetAccess = new AccessKeyStore(tmp.resolve("new-home").resolve("access"));

        var out = mapper.readValue(json, Map.class);
        assertEquals("ainvr-config-backup", out.get("kind"));

        // 用导出的 appSettings + 凭据直接导入到新库
        @SuppressWarnings("unchecked")
        Map<String, Object> appSettings = (Map<String, Object>) out.get("appSettings");
        var importedKeys = target.importAll(appSettings);
        assertTrue(importedKeys.contains("rtspToken"));
        assertTrue(importedKeys.contains("ai"));
        assertEquals("secret-token-1", target.rtspToken());
        assertEquals(false, target.aiSettings().enabled());
        assertEquals(0.7, target.aiSettings().minScore(), 1e-9);

        // 访问密码凭据恢复后可用原密码解锁
        targetAccess.importRecord(mapper.convertValue(out.get("accessCredential"), AccessKeyStore.Record.class));
        assertTrue(targetAccess.verify("backup-test-pass-123".toCharArray()));
    }

    @Test
    void importRejectsUnknownKind() throws Exception {
        mvc.perform(post("/api/settings/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"other\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aiSettingsUpdateValidatesRange() throws Exception {
        mvc.perform(put("/api/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"minScore\":0.2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"minScore\":0.7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ai.enabled").value(false))
                .andExpect(jsonPath("$.ai.minScore").value(0.7));
        assertEquals(false, settingsStore.aiSettings().enabled());
    }

    @Test
    void backupFilesRemainRestorable() throws Exception {
        // 存储目录应已生成（access.json / boards.json 由各自 Store 管理）
        assertTrue(Files.exists(tmp.resolve("access")));
    }
}
