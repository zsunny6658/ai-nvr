package com.ainvr.web.service;

import com.ainvr.model.CameraDevice;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import com.ainvr.store.EventStore.AlarmEvent;
import com.ainvr.store.MotionSettings;
import com.ainvr.store.MotionZone;
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
    private AppSettingsStore settings;
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

        settings = Mockito.mock(AppSettingsStore.class);
        Mockito.when(settings.motion()).thenAnswer(inv -> config.get());
        Mockito.when(settings.motionExcludeZones()).thenReturn(Map.of());
        Mockito.when(settings.excludeZonesFor(anyString())).thenReturn(List.of());

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
        tickAfter(1000); // v1.2.21：仍在合并窗口（4×冷却=20s）内 → 合并进首条（×1），不新增
        assertEquals(1, events.size());
        AlarmEvent merged = events.list(EventStore.Filter.of(DID, "motion", null, null,
                null, null, 10, 0)).items().get(0);
        assertEquals(1, merged.repeat(), "持续变动应合并计数");
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

    // ===================== v1.2.19 排除区域（ROI） =====================

    /**
     * 左半幅反相构造下：整幅变化必然触发；排除左半后右半静止，帧差为 0，不触发。
     * 掩码只影响评分，不影响基准建立与低照度/静默等既有语义。
     */
    @Test
    void excludedZoneSuppressesMotionInMaskedArea() throws Exception {
        config.set(cfg(true, 0.12, 1, 5, 2, 0));

        // 不排除：左半反相变化 → 照常触发
        tickAfter(1000);
        tickAfter(1000);
        currentFrame.set(image(false));
        tickAfter(1000);
        tickAfter(1000);
        assertEquals(1, events.size(), "无排除区时左半变化应触发");

        // 排除左半（bx 0..3 全高）：右半始终黑，帧差应恒为 0
        java.util.List<MotionZone> zones = List.of(new MotionZone(0, 0, 0.5, 1.0));
        Mockito.when(settings.motionExcludeZones()).thenReturn(Map.of(DID, zones));
        Mockito.when(settings.excludeZonesFor(anyString())).thenReturn(zones);

        tickAfter(1000); // 重建基准（A 画面）
        tickAfter(1000);
        currentFrame.set(image(false)); // 左半反相（被排除），右半不变
        tickAfter(1000);
        tickAfter(1000);
        assertEquals(1, events.size(), "排除左半后不应新增事件（仍为之前的 1 条）");
        Map<?, ?> perDevice = (Map<?, ?>) detector.status().get("devices");
        Map<?, ?> one = (Map<?, ?>) perDevice.get(DID);
        assertEquals(32, ((Number) one.get("excludedBlocks")).intValue(), "应排除 32 个侦测块");
    }

    @Test
    void fullyMaskedFrameNeverTriggers() throws Exception {
        config.set(cfg(true, 0.12, 1, 5, 2, 0));
        java.util.List<MotionZone> zones = List.of(new MotionZone(0, 0, 1.0, 1.0));
        Mockito.when(settings.motionExcludeZones()).thenReturn(Map.of(DID, zones));
        Mockito.when(settings.excludeZonesFor(anyString())).thenReturn(zones);

        tickAfter(1000);
        tickAfter(1000);
        currentFrame.set(image(false));
        for (int i = 0; i < 4; i++) {
            tickAfter(1000);
        }
        assertEquals(0, events.size(), "全画面排除等于禁用侦测，不应触发");
        Map<?, ?> perDevice = (Map<?, ?>) detector.status().get("devices");
        Map<?, ?> one = (Map<?, ?>) perDevice.get(DID);
        assertEquals("排除区域覆盖全画面，本帧未判定", one.get("lastError"));
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
