package com.ainvr.web.api;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.MotionSettings;
import com.ainvr.web.service.MotionDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 运动侦测配置 / 状态 / 手动采样接口的 HTTP 层与参数校验。 */
class MotionWebTest {

    private MockMvc mvc;
    private AppSettingsStore settings;
    private MotionDetector detector;
    private final AtomicReference<MotionSettings> current =
            new AtomicReference<>(MotionSettings.DEFAULTS);

    @BeforeEach
    void setUp() {
        settings = Mockito.mock(AppSettingsStore.class);
        detector = Mockito.mock(MotionDetector.class);
        Mockito.when(settings.motion()).thenAnswer(inv -> current.get());
        Mockito.doAnswer(inv -> {
            current.set(inv.getArgument(0));
            return null;
        }).when(settings).saveMotion(Mockito.any(MotionSettings.class));
        mvc = MockMvcBuilders.standaloneSetup(new MotionController(settings, detector))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void configDefaultsToDisabled() throws Exception {
        mvc.perform(get("/api/motion/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.threshold").value(0.12))
                .andExpect(jsonPath("$.confirmFrames").value(2));
    }

    @Test
    void partialUpdateKeepsUnspecifiedFieldsAndAppliesImmediately() throws Exception {
        mvc.perform(put("/api/motion/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"threshold\":0.2,\"dids\":[\"d1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.threshold").value(0.2))
                .andExpect(jsonPath("$.cooldownSec").value(30))
                .andExpect(jsonPath("$.dids[0]").value("d1"));

        ArgumentCaptor<MotionSettings> captor = ArgumentCaptor.forClass(MotionSettings.class);
        Mockito.verify(settings).saveMotion(captor.capture());
        assertTrue(captor.getValue().enabled());
        assertEquals(List.of("d1"), captor.getValue().dids());
        assertEquals(30, captor.getValue().cooldownSec());
    }

    /**
     * v2.2.4 回归：时段字段省略 / null 必须沿用当前值（旧前端曾把空值转成 -1 发上来，
     * 导致「activeEndMin 需在 0.0 ~ 1440.0 之间」报错且保存失败）；边界值 1440（24:00）合法。
     */
    @Test
    void scheduleFieldsNullKeepCurrentAndBoundary1440Accepted() throws Exception {
        mvc.perform(put("/api/motion/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduleEnabled\":true,\"activeStartMin\":null,\"activeEndMin\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeStartMin").value(0))
                .andExpect(jsonPath("$.activeEndMin").value(1440));

        ArgumentCaptor<MotionSettings> captor = ArgumentCaptor.forClass(MotionSettings.class);
        Mockito.verify(settings).saveMotion(captor.capture());
        assertTrue(captor.getValue().scheduleEnabled());
        assertEquals(0, captor.getValue().activeStartMin());
        assertEquals(1440, captor.getValue().activeEndMin());

        mvc.perform(put("/api/motion/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activeEndMin\":1440}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeEndMin").value(1440));
    }

    @Test
    void invalidRangeReturns400WithoutPersisting() throws Exception {
        mvc.perform(put("/api/motion/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threshold\":9.0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(put("/api/motion/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intervalSec\":0}"))
                .andExpect(status().isBadRequest());
        Mockito.verify(settings, Mockito.never()).saveMotion(Mockito.any());
    }

    @Test
    void statusAndManualSampleReturnObservableDetails() throws Exception {
        Mockito.when(detector.status()).thenReturn(Map.of(
                "enabled", true, "threshold", 0.12, "events", Map.of("total", 3),
                "devices", Map.of("d1", Map.of("lastScore", 0.25, "triggered", 1))));
        Mockito.when(detector.sample("d1")).thenReturn(new MotionDetector.SampleResult(
                "d1", 0.25, 0.12, 120, 2, true, false, 30, 0, ""));

        mvc.perform(get("/api/motion/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.events.total").value(3))
                .andExpect(jsonPath("$.devices.d1.lastScore").value(0.25));

        mvc.perform(post("/api/motion/sample/d1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.did").value("d1"))
                .andExpect(jsonPath("$.score").value(0.25))
                .andExpect(jsonPath("$.triggered").value(true))
                .andExpect(jsonPath("$.confirm").value(2));
    }
}
