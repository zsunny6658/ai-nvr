package com.invr.web.api;

import com.invr.hub.StreamHub;
import com.invr.model.CameraDevice;
import com.invr.rtsp.RtspServer;
import com.invr.store.AppSettingsStore;
import com.invr.store.EventStore;
import com.invr.store.MotionSettings;
import com.invr.web.service.DeviceCatalog;
import com.invr.web.service.HlsService;
import com.invr.web.service.LiveFrameService;
import com.invr.web.service.RecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.isNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 首页概览聚合：字段正确、单项数据源异常时不影响其它项。 */
class DashboardWebTest {

    private DeviceCatalog catalog;
    private RecordingService recordings;
    private EventStore events;
    private StreamHub hub;
    private HlsService hls;
    private AppSettingsStore settings;
    private LiveFrameService frames;
    private RtspServer rtsp;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        catalog = Mockito.mock(DeviceCatalog.class);
        recordings = Mockito.mock(RecordingService.class);
        events = Mockito.mock(EventStore.class);
        hub = Mockito.mock(StreamHub.class);
        hls = Mockito.mock(HlsService.class);
        settings = Mockito.mock(AppSettingsStore.class);
        frames = Mockito.mock(LiveFrameService.class);
        rtsp = Mockito.mock(RtspServer.class);
        mvc = MockMvcBuilders.standaloneSetup(new DashboardController(
                catalog, recordings, events, hub, hls, settings, frames, rtsp))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        Mockito.when(catalog.all()).thenReturn(List.of(
                new CameraDevice("d1", "客厅", "model.a", "mac1", "192.168.1.10", true),
                new CameraDevice("d2", "门口", "model.b", "mac2", "192.168.1.11", false)));
        Mockito.when(recordings.isRecording("d1")).thenReturn(true);
        Mockito.when(hls.isPreviewing("d1")).thenReturn(true);
        Mockito.when(settings.motion()).thenReturn(MotionSettings.DEFAULTS);
        Mockito.when(hub.activeUpstreamCount()).thenReturn(2);
        Mockito.when(hub.activeDids()).thenReturn(List.of("d1", "d2"));
        Mockito.when(frames.activeSources()).thenReturn(1);
        Mockito.when(rtsp.getPort()).thenReturn(9554);
        Mockito.when(recordings.rootDir()).thenReturn(new File("/tmp/invr-rec"));
        Mockito.when(events.stats()).thenReturn(Map.of(
                "total", 9, "today", 3, "unhandled", 2, "byType", Map.of("motion", 9)));
    }

    @Test
    void aggregatesAllSources() throws Exception {
        Mockito.when(recordings.list(isNull())).thenReturn(List.of(
                Map.of("size", 1024L), Map.of("size", 2048L), Map.of("size", 4096L)));

        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices.total").value(2))
                .andExpect(jsonPath("$.devices.online").value(1))
                .andExpect(jsonPath("$.devices.offline").value(1))
                .andExpect(jsonPath("$.devices.recording").value(1))
                .andExpect(jsonPath("$.devices.previewing").value(1))
                .andExpect(jsonPath("$.streams.activeUpstreams").value(2))
                .andExpect(jsonPath("$.streams.activeDids[0]").value("d1"))
                .andExpect(jsonPath("$.streams.activeFrameSources").value(1))
                .andExpect(jsonPath("$.recordings.files").value(3))
                .andExpect(jsonPath("$.recordings.bytes").value(7168))
                .andExpect(jsonPath("$.recordings.dir").value("/tmp/invr-rec"))
                .andExpect(jsonPath("$.events.today").value(3))
                .andExpect(jsonPath("$.events.unhandled").value(2))
                .andExpect(jsonPath("$.motion.enabled").value(false))
                .andExpect(jsonPath("$.rtspPort").value(9554))
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    void singleSourceFailureDoesNotBreakWholeDashboard() throws Exception {
        Mockito.when(recordings.list(isNull())).thenThrow(new IOException("目录暂不可读"));
        Mockito.when(events.stats()).thenThrow(new RuntimeException("索引未就绪"));
        Mockito.when(recordings.isRecording("d1")).thenThrow(new RuntimeException("状态异常"));

        mvc.perform(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.devices.total").value(2))
                .andExpect(jsonPath("$.devices.online").value(1))
                .andExpect(jsonPath("$.devices.recording").value(0))
                .andExpect(jsonPath("$.recordings.files").value(0))
                .andExpect(jsonPath("$.recordings.bytes").value(0))
                .andExpect(jsonPath("$.events.total").value(0))
                .andExpect(jsonPath("$.events.today").value(0))
                .andExpect(jsonPath("$.events.unhandled").value(0));
    }
}
