package com.invr.web.service;

import com.invr.hub.CameraConnector;
import com.invr.hub.StreamHub;
import com.invr.model.CameraDevice;
import com.invr.rtsp.RtspServer;
import com.invr.store.AppSettingsStore;
import com.invr.store.MotionSettings;
import com.invr.web.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 设备自检：浅检零副作用、离线跳过深检、未知设备 404。
 *
 * <p>深检的正常取流路径依赖 Linux 上的 FFmpeg native 与真实摄像头，属于 NAS 验收范围；
 * 这里不构造 {@code SnapshotDecoder}，避免在无对应平台原生库的 CI/开发机上误判。</p>
 */
class StreamProbeServiceTest {

    private DeviceCatalog catalog;
    private StreamHub hub;
    private CameraConnector connector;
    private RecordingService recordings;
    private HlsService hls;
    private AppSettingsStore settings;
    private RtspServer rtsp;
    private StreamProbeService service;

    private final CameraDevice online = new CameraDevice(
            "d1", "客厅", "chuangmi.camera.039a01", "mac1", "192.168.31.148", true);
    private final CameraDevice offline = new CameraDevice(
            "d2", "门口", "chuangmi.camera.079ac1", "mac2", "192.168.31.191", false);

    @BeforeEach
    void setUp() {
        catalog = Mockito.mock(DeviceCatalog.class);
        hub = Mockito.mock(StreamHub.class);
        connector = Mockito.mock(CameraConnector.class);
        recordings = Mockito.mock(RecordingService.class);
        hls = Mockito.mock(HlsService.class);
        settings = Mockito.mock(AppSettingsStore.class);
        rtsp = Mockito.mock(RtspServer.class);
        service = new StreamProbeService(catalog, hub, connector, recordings, hls, settings, rtsp);

        Mockito.when(catalog.findOrRefresh("d1")).thenReturn(online);
        Mockito.when(catalog.findOrRefresh("d2")).thenReturn(offline);
        Mockito.when(hub.describe("d1")).thenReturn(Map.of(
                "active", true, "subscribers", 2, "healthy", true,
                "lastFrameAgeMs", 1200L, "generation", 3));
        Mockito.when(settings.qualityFor("d1")).thenReturn("HD");
        Mockito.when(settings.ptzMode()).thenReturn("operation");
        Mockito.when(settings.motion()).thenReturn(new MotionSettings(
                true, 0.12, 2, 30, 2, 8, 30, 2000, List.of("d1")));
        Mockito.when(recordings.isRecording("d1")).thenReturn(true);
        Mockito.when(hls.isPreviewing("d1")).thenReturn(true);
        Mockito.when(rtsp.getPort()).thenReturn(9554);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shallowProbeAggregatesStateWithoutSubscribing() {
        Map<String, Object> r = service.probe("d1", false);

        assertEquals("d1", r.get("did"));
        assertEquals("客厅", r.get("name"));
        assertEquals(true, r.get("online"));
        assertEquals(true, r.get("recording"));
        assertEquals(true, r.get("previewing"));
        assertEquals("HD", r.get("quality"));
        assertEquals("operation", r.get("ptzMode"));
        assertEquals(9554, r.get("rtspPort"));
        assertEquals(false, r.get("deep"));
        assertFalse(r.containsKey("media"));
        assertEquals(2, ((Map<String, Object>) r.get("stream")).get("subscribers"));
        assertEquals(true, ((Map<String, Object>) r.get("motion")).get("watched"));
        Mockito.verify(hub, Mockito.never()).subscribe(anyString(), anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deepProbeOnOfflineDeviceSkipsStreaming() {
        Map<String, Object> r = service.probe("d2", true);
        Map<String, Object> media = (Map<String, Object>) r.get("media");

        assertEquals(true, r.get("deep"));
        assertEquals(false, media.get("ok"));
        assertTrue(((String) media.get("error")).contains("离线"));
        Mockito.verify(hub, Mockito.never()).subscribe(anyString(), anyString(), any(), any());
    }

    @Test
    void unknownDeviceThrows404() {
        ApiException e = assertThrows(ApiException.class, () -> service.probe("missing", false));
        assertEquals(404, e.getStatus());
    }
}
