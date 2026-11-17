package com.ainvr.web.service;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import com.ainvr.store.MotionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 运动触发自动录像状态机：起停、尾延时、手动录像优先、关闭收敛、手动停止抑制、重启恢复。
 * 录像服务与侦测器用 mock，事件库用真实临时目录，时钟可推进，不依赖定时器。
 */
class MotionRecordingServiceTest {

    private static final String DID = "1072813513";

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock();
    private final AtomicReference<MotionSettings> config =
            new AtomicReference<>(MotionSettings.DEFAULTS);
    private final AtomicReference<Map<String, Long>> motionAt =
            new AtomicReference<>(Map.of());
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    private RecordingService recordings;
    private EventStore events;
    private MotionRecordingService service;

    @BeforeEach
    void setUp() throws Exception {
        recordings = Mockito.mock(RecordingService.class);
        Mockito.when(recordings.isRecording(anyString()))
                .thenAnswer(i -> active.contains(i.getArgument(0)));
        Mockito.when(recordings.isPersistentRecording(anyString())).thenReturn(false);
        Mockito.doAnswer(i -> {
            active.add(i.getArgument(0));
            return Map.of("did", i.getArgument(0));
        }).when(recordings).startTransient(anyString(), Mockito.nullable(Long.class));
        Mockito.doAnswer(i -> {
            active.remove(i.getArgument(0));
            return Map.of("did", i.getArgument(0));
        }).when(recordings).stopTransient(anyString());

        MotionDetector detector = Mockito.mock(MotionDetector.class);
        Mockito.when(detector.motionDids())
                .thenAnswer(i -> new HashSet<>(motionAt.get().keySet()));
        Mockito.when(detector.lastMotionAtMs(anyString()))
                .thenAnswer(i -> motionAt.get().getOrDefault(i.getArgument(0), 0L));
        Mockito.when(detector.deviceName(anyString()))
                .thenAnswer(i -> i.getArgument(0));

        AppSettingsStore settings = Mockito.mock(AppSettingsStore.class);
        Mockito.when(settings.motion()).thenAnswer(i -> config.get());

        events = new EventStore(dir);
        service = new MotionRecordingService(recordings, detector, settings, events,
                clock, dir.resolve("motion-recordings.json"));
    }

    private static MotionSettings cfg(boolean enabled, boolean autoRecord, int tailSec) {
        return new MotionSettings(enabled, 0.12, 2, 30, 2, 8, 30, 2000,
                List.of(), autoRecord, tailSec);
    }

    private void motionNow() {
        motionAt.set(Map.of(DID, clock.millis()));
    }

    @Test
    void defaultsNeverStartRecording() {
        config.set(cfg(true, false, 20));
        motionNow();
        service.tick();
        verify(recordings, never()).startTransient(anyString(), Mockito.nullable(Long.class));
        assertTrue(((List<?>) service.status().get("owned")).isEmpty());
    }

    @Test
    void startsOnMotionAndStopsAfterTail() {
        config.set(cfg(true, true, 20));
        motionNow();
        service.tick();
        verify(recordings).startTransient(DID, null);
        assertTrue(((List<?>) service.status().get("owned")).contains(DID));
        // 开录时写一条「自动录像」事件，事件页可见
        assertEquals(1, events.size());

        // 画面持续变化：尾窗口内不重复开录、不提前停录
        clock.advance(10_000);
        motionAt.set(Map.of(DID, clock.millis()));
        service.tick();
        verify(recordings, Mockito.times(1)).startTransient(anyString(), Mockito.nullable(Long.class));

        // 静止超过尾延时：封段停录
        clock.advance(21_000);
        service.tick();
        verify(recordings).stopTransient(DID);
        assertTrue(((List<?>) service.status().get("owned")).isEmpty());
    }

    @Test
    void neverTouchesPersistentOrAlreadyRunningRecording() {
        config.set(cfg(true, true, 20));
        motionNow();
        Mockito.when(recordings.isPersistentRecording(DID)).thenReturn(true);
        service.tick();
        verify(recordings, never()).startTransient(anyString(), Mockito.nullable(Long.class));

        Mockito.when(recordings.isPersistentRecording(DID)).thenReturn(false);
        active.add(DID); // 用户手动开启的录像会话
        service.tick();
        verify(recordings, never()).startTransient(anyString(), Mockito.nullable(Long.class));
        verify(recordings, never()).stopTransient(anyString());
    }

    @Test
    void disablingAutoRecordClosesOwnedSessions() {
        config.set(cfg(true, true, 20));
        motionNow();
        service.tick();
        verify(recordings).startTransient(DID, null);

        config.set(cfg(false, false, 20));
        service.tick();
        verify(recordings).stopTransient(DID);
        assertTrue(active.isEmpty());
    }

    @Test
    void manualStopSuppressesImmediateRestart() {
        config.set(cfg(true, true, 20));
        motionNow();
        service.tick();
        verify(recordings).startTransient(DID, null);

        // 用户在界面上点了「停止录像」：会话消失但状态机还持有 owned
        active.remove(DID);
        service.tick();
        assertTrue(((List<?>) service.status().get("owned")).isEmpty());
        assertFalse(((Map<?, ?>) service.status().get("manualStopUntil")).isEmpty());

        // 冷却期内即便继续有运动，也不与用户对着拉起录像
        motionAt.set(Map.of(DID, clock.millis()));
        service.tick();
        verify(recordings, Mockito.times(1)).startTransient(anyString(), Mockito.nullable(Long.class));

        // 冷却期过后恢复自动
        clock.advance(31_000);
        motionAt.set(Map.of(DID, clock.millis()));
        service.tick();
        verify(recordings, Mockito.times(2)).startTransient(anyString(), Mockito.nullable(Long.class));
    }

    @Test
    void recoveryFromFileRestartsOnceAndConverges() throws Exception {
        Files.write(dir.resolve("motion-recordings.json"),
                ("[\"" + DID + "\"]").getBytes(StandardCharsets.UTF_8));
        config.set(cfg(true, true, 20));
        // 重新构造，模拟进程重启后读取持久化状态
        MotionDetector detector = Mockito.mock(MotionDetector.class);
        Mockito.when(detector.motionDids()).thenReturn(Set.of());
        Mockito.when(detector.deviceName(anyString())).thenAnswer(i -> i.getArgument(0));
        AppSettingsStore settings = Mockito.mock(AppSettingsStore.class);
        Mockito.when(settings.motion()).thenAnswer(i -> config.get());
        MotionRecordingService restarted = new MotionRecordingService(
                recordings, detector, settings, events, clock, dir.resolve("motion-recordings.json"));

        restarted.tick();
        verify(recordings).startTransient(DID, null);

        // 没有新的运动：尾延时窗口后自动停录，不会变成永久录像
        clock.advance(21_000);
        restarted.tick();
        verify(recordings).stopTransient(DID);
        assertTrue(((List<?>) restarted.status().get("owned")).isEmpty());
    }

    /** 可控时钟：避免单测依赖真实等待或调度线程。 */
    private static final class MutableClock extends Clock {
        private long ms = 1_700_000_000_000L;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(ms);
        }

        @Override
        public long millis() {
            return ms;
        }

        void advance(long delta) {
            ms += delta;
        }
    }
}
