package com.ainvr.web.service;

import com.ainvr.hub.CameraConnector;
import com.ainvr.hub.StreamHub;
import com.ainvr.model.CameraDevice;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.RecordingStateStore;
import com.ainvr.web.config.NvrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v2.3.1 第十八轮热修回归锁：摄像头休眠时暂停持续录像、唤醒后自动恢复。
 *
 * <p>此前休眠停录像走 {@link RecordingService#stop}，会把「期望持续录像」标记一并清掉，
 * 唤醒后 reconcile 无据可依、永不续录。修后 {@code stopForSleep} 只解除订阅并保留标记
 * （记入 sleepPaused），reconcile 跳过休眠暂停设备（不打断休眠取流闸门），
 * {@code resumeAfterSleep} 在唤醒时恢复。</p>
 */
class RecordingSleepPauseTest {

    private static final String DID = "1058566341";

    @TempDir
    Path dir;

    private StreamHub hub;
    private CameraConnector connector;
    private RecordingStateStore stateStore;
    private RecordingService svc;

    @BeforeEach
    void setUp() {
        hub = Mockito.mock(StreamHub.class);
        connector = Mockito.mock(CameraConnector.class);
        stateStore = Mockito.mock(RecordingStateStore.class);
        NvrProperties props = Mockito.mock(NvrProperties.class);
        when(props.getRecordingDir()).thenReturn(dir.toString());
        when(props.getRecordSegmentSec()).thenReturn(60L);        when(props.getRecordRetentionDays()).thenReturn(0);
        AppSettingsStore settings = Mockito.mock(AppSettingsStore.class);
        when(settings.recordingDir()).thenReturn(dir.toString());
        svc = new RecordingService(props, hub, connector, stateStore, settings);
        when(stateStore.cameras()).thenReturn(Map.of(DID, 60L));
        when(connector.findDevice(DID)).thenReturn(
                new CameraDevice(DID, "客厅", "isa.camera.hlc8", "", "192.168.31.68", true));
    }

    @Test
    void sleepPauseKeepsDesiredStateAndReconcileSkips() {
        svc.start(DID, 60L);
        assertTrue(svc.isRecording(DID));
        svc.stopForSleep(DID);
        // 订阅已解除，但「期望持续录像」标记保留（不被 disable）
        assertFalse(svc.isRecording(DID));
        verify(stateStore, never()).disable(anyString());
        assertTrue(svc.isPersistentRecording(DID));
        // reconcile 在休眠暂停期间不得重新拉起订阅（否则打断休眠取流闸门）
        svc.reconcile();
        verify(hub, times(1)).subscribe(eq(DID), anyString(), any(), any());
    }

    @Test
    void resumeAfterSleepRestoresRecording() {
        svc.start(DID, 60L);
        svc.stopForSleep(DID);
        assertFalse(svc.isRecording(DID));
        svc.resumeAfterSleep(DID);
        assertTrue(svc.isRecording(DID));
        verify(hub, times(2)).subscribe(eq(DID), anyString(), any(), any());
    }

    @Test
    void resumeWithoutSleepPauseIsNoop() {
        svc.resumeAfterSleep(DID);
        assertFalse(svc.isRecording(DID));
        verify(hub, never()).subscribe(anyString(), anyString(), any(), any());
    }
}
