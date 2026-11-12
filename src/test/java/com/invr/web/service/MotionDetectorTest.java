package com.invr.web.service;

import com.invr.model.CameraDevice;
import com.invr.store.AppSettingsStore;
import com.invr.store.EventStore;
import com.invr.store.MotionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 运动侦测状态机：基准帧比对、连续确认、冷却、云台静默、低照度跳过与开关行为。
 * 帧服务 / 设备目录 / 设置用 mock，事件库用真实临时目录，时钟可推进，不依赖定时器。
 */
class MotionDetectorTest {

    private static final String DID = "1072813513";

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock();
    private final AtomicReference<byte[]> currentFrame = new AtomicReference<>();
    private final AtomicReference<MotionSettings> config =
            new AtomicReference<>(MotionSettings.DEFAULTS);

    private LiveFrameService frames;
    private EventStore events;
    private MotionDetector detector;

    @BeforeEach
    void setUp() throws Exception {
        frames = Mockito.mock(LiveFrameService.class);
        Mockito.when(frames.frame(DID)).thenAnswer(inv -> currentFrame.get());

        DeviceCatalog catalog = Mockito.mock(DeviceCatalog.class);
        CameraDevice device = new CameraDevice(DID, "客厅", "chuangmi.camera.039a01",
                "", "192.168.31.148", true);
        Mockito.when(catalog.all()).thenReturn(List.of(device));
        Mockito.when(catalog.find(DID)).thenReturn(Optional.of(device));

        AppSettingsStore settings = Mockito.mock(AppSettingsStore.class);
        Mockito.when(settings.motion()).thenAnswer(inv -> config.get());

        events = new EventStore(dir);
        detector = new MotionDetector(frames, catalog, settings, events, clock);
        currentFrame.set(image(true));
    }

    private static MotionSettings cfg(boolean enabled, double threshold, int interval,
                                      int cooldown, int confirm, int silent) {
        return new MotionSettings(enabled, threshold, interval, cooldown, confirm, silent,
                30, 2000, List.of());
    }

    private void tickAfter(long advanceMs) {
        clock.advance(advanceMs);
        detector.tick();
    }

    /** 左右半幅反相：两种画面平均亮度相同但感知哈希差异极大，不会触发低照度跳过。 */
    private static byte[] image(boolean leftWhite) throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(leftWhite ? Color.WHITE : Color.BLACK);
        g.fillRect(0, 0, 32, 64);
        g.setColor(leftWhite ? Color.BLACK : Color.WHITE);
        g.fillRect(32, 0, 32, 64);
        g.dispose();
        return encode(img);
    }

    private static byte[] solid(Color c) throws IOException {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        return encode(img);
    }

    private static byte[] encode(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void hashIsStableForSameSceneAndDistinctForInvertedScene() throws Exception {
        MotionDetector.HashResult a = MotionDetector.hash(image(true));
        MotionDetector.HashResult a2 = MotionDetector.hash(image(true));
        MotionDetector.HashResult b = MotionDetector.hash(image(false));
        assertNotNull(a);
        assertEquals(a.bits(), a2.bits());
        assertTrue(Long.bitCount(a.bits() ^ b.bits()) >= 8);
        assertTrue(a.luma() > MotionDetector.MIN_LUMA);
        // 回归：64 bit 左移后最高位为 1 时 long 为负数。曾误用 baselineBits < 0 当「无基准」
        // 哨兵，导致每一帧都被当成首帧、侦测永远不触发；本用例固定覆盖该位模式。
        assertTrue(a.bits() < 0, "半幅白图案的哈希最高位应为 1（负 long）");
    }

    @Test
    void disabledDetectorDoesNotTouchFrameService() {
        config.set(cfg(false, 0.12, 1, 5, 2, 0));
        tickAfter(1000);
        verify(frames, never()).frame(anyString());
        assertEquals(0, events.size());
    }

    @Test
    void staticSceneProducesNoEvents() {
        config.set(cfg(true, 0.12, 1, 5, 2, 0));
        for (int i = 0; i < 6; i++) {
            tickAfter(1000);
        }
        assertEquals(0, events.size());
        Map<?, ?> perDevice = (Map<?, ?>) detector.status().get("devices");
        Map<?, ?> one = (Map<?, ?>) perDevice.get(DID);
        assertEquals(6L, ((Number) one.get("samples")).longValue());
        assertEquals(0.0, ((Number) one.get("lastScore")).doubleValue());
    }

    @Test
    void changedSceneTriggersExactlyOnceAfterConfirmFrames() throws Exception {
        config.set(cfg(true, 0.12, 1, 5, 2, 0));
        tickAfter(1000); // 建基准
        tickAfter(1000);
        currentFrame.set(image(false));
        tickAfter(1000); // confirm=1
        MotionDetector.SampleResult second = detector.sample(DID);
        assertTrue(second.triggered());
        assertEquals(1, events.size());
        assertNotNull(events.find(1).orElseThrow().snapshot());

        // 触发后新画面成为基准，静止不再重复报警
        tickAfter(1000);
        tickAfter(1000);
        assertEquals(1, events.size());
        assertEquals(1L, ((Number) ((Map<?, ?>) ((Map<?, ?>) detector.status()
                .get("devices")).get(DID)).get("triggered")).longValue());
    }

    @Test
    void cooldownSuppressesImmediateRepeatButAllowsLaterEvent() throws Exception {
        config.set(cfg(true, 0.12, 1, 5, 2, 0));
        tickAfter(1000);
        tickAfter(1000);
        currentFrame.set(image(false));
        tickAfter(1000);
        tickAfter(1000); // 触发 #1
        assertEquals(1, events.size());

        currentFrame.set(image(true));
        for (int i = 0; i < 3; i++) {
            tickAfter(1000); // 冷却期内不累加确认
        }
        assertEquals(1, events.size());

        clock.advance(3000);
        tickAfter(1000); // 冷却结束，画面仍偏离基准
        tickAfter(1000);
        assertEquals(2, events.size());
    }

    @Test
    void ptzSilenceAbsorbsSceneChangeAndResetsBaseline() throws Exception {
        config.set(cfg(true, 0.12, 1, 5, 2, 10));
        tickAfter(1000); // 建基准 A
        detector.noteMotor(DID);
        currentFrame.set(image(false));
        for (int i = 0; i < 3; i++) {
            MotionDetector.SampleResult r = detector.sample(DID);
            assertTrue(r.silent());
            clock.advance(1000);
        }
        assertEquals(0, events.size());

        // 静默结束后基准已是 B，B 静止不报警；画面再次变化才触发
        currentFrame.set(image(false));
        tickAfter(12000);
        assertEquals(0, events.size());
        currentFrame.set(image(true));
        tickAfter(1000);
        tickAfter(1000);
        assertEquals(1, events.size());
    }

    @Test
    void lowLumaFramesAreSkipped() throws Exception {
        config.set(cfg(true, 0.12, 1, 5, 2, 0));
        currentFrame.set(solid(Color.BLACK));
        tickAfter(1000);
        assertEquals(0, events.size());
        Map<?, ?> one = (Map<?, ?>) ((Map<?, ?>) detector.status().get("devices")).get(DID);
        assertEquals("低照度帧已跳过", one.get("lastError"));
        assertFalse(((String) one.get("lastError")).isEmpty());
    }

    @Test
    void manualSampleReturnsExplainableDecision() {
        config.set(cfg(true, 0.12, 1, 5, 2, 0));
        MotionDetector.SampleResult first = detector.sample(DID);
        assertEquals(0.0, first.score()); // 首帧只建基准
        assertFalse(first.triggered());
        assertEquals(0.12, first.threshold());
    }

    private static final class MutableClock extends Clock {
        private long millis = 10_000_000L;

        void advance(long ms) {
            millis += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
