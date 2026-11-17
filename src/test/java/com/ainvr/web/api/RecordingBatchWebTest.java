package com.ainvr.web.api;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.web.config.NvrProperties;
import com.ainvr.web.service.RecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1.2.2 批量接口的 HTTP 层验证：JSON 请求体映射到 {@code names}、汇总结构、
 * 参数校验错误体、以及 ZIP 流式响应的响应头与实际字节内容。
 *
 * <p>用 standaloneSetup 单独装载 Controller，不启动 Spring 上下文、不经过门禁拦截器
 * （门禁由 WebSecurityConfig 全局覆盖，已在本地实启时验证）。</p>
 */
class RecordingBatchWebTest {

    private static final String A = "rec-1072813513-001-20261111-120000.mp4";
    private static final String B = "rec-1072813513-002-20261111-120200.mp4";

    @TempDir
    Path dir;

    private MockMvc mvc;

    private static final class TestService extends RecordingService {
        TestService(NvrProperties props, AppSettingsStore settings) {
            super(props, null, null, null, settings);
        }

        @Override
        protected Set<String> writingSegments() {
            return Collections.emptySet();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        NvrProperties props = new NvrProperties();
        props.setRecordingDir(dir.toString());
        RecordingService svc = new TestService(props, Mockito.mock(AppSettingsStore.class));
        Files.write(dir.resolve(A), "AAA".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(B), "BBB".getBytes(StandardCharsets.UTF_8));
        mvc = MockMvcBuilders.standaloneSetup(new RecordingController(svc))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void batchDeleteMapsJsonBodyAndReturnsSummary() throws Exception {
        mvc.perform(post("/api/recordings/batch/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[\"" + A + "\",\"" + B + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(2))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andExpect(jsonPath("$.deleted[0]").value(A));

        assertEquals(false, Files.exists(dir.resolve(A)));
        assertEquals(false, Files.exists(dir.resolve(B)));
    }

    @Test
    void batchDeleteReturnsJsonErrorForEmptySelection() throws Exception {
        mvc.perform(post("/api/recordings/batch/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void batchDownloadStreamsValidZipWithHeaders() throws Exception {
        MvcResult async = mvc.perform(post("/api/recordings/batch/download")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"names\":[\"" + A + "\",\"" + B + "\",\"../evil.mp4\"]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/zip"))
                .andExpect(header().string("X-aiNVR-Zip-Files", "2"))
                .andExpect(header().string("X-aiNVR-Zip-Skipped", "1"))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andReturn();

        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(
                new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                entries.put(e.getName(), zin.readAllBytes());
            }
        }
        assertEquals(Set.of(A, B), entries.keySet());
        assertArrayEquals("AAA".getBytes(StandardCharsets.UTF_8), entries.get(A));
        assertArrayEquals("BBB".getBytes(StandardCharsets.UTF_8), entries.get(B));
    }
}
