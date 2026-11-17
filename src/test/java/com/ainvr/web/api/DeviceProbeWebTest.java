package com.ainvr.web.api;

import com.ainvr.web.service.StreamProbeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 设备自检接口的路径、deep 参数与异常映射。 */
class DeviceProbeWebTest {

    private StreamProbeService probes;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        probes = Mockito.mock(StreamProbeService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DeviceProbeController(probes))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        Mockito.when(probes.probe("d1", false)).thenReturn(Map.of("did", "d1", "online", true, "deep", false));
        Mockito.when(probes.probe("d1", true)).thenReturn(Map.of("did", "d1", "online", true,
                "deep", true, "media", Map.of("codec", "H.265 / HEVC")));
    }

    @Test
    void shallowProbeByDefault() throws Exception {
        mvc.perform(get("/api/devices/d1/probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.did").value("d1"))
                .andExpect(jsonPath("$.deep").value(false));
        Mockito.verify(probes).probe("d1", false);
    }

    @Test
    void deepQueryTriggersStreamProbe() throws Exception {
        mvc.perform(get("/api/devices/d1/probe").param("deep", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deep").value(true))
                .andExpect(jsonPath("$.media.codec").value("H.265 / HEVC"));
        Mockito.verify(probes).probe("d1", true);
    }

    @Test
    void unknownDeviceReturns404() throws Exception {
        Mockito.when(probes.probe("missing", false))
                .thenThrow(ApiException.notFound("设备列表中未找到 did=missing"));
        mvc.perform(get("/api/devices/missing/probe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
