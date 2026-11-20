package com.ainvr.web.api;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.DataDir;
import com.ainvr.store.EventStore;
import com.ainvr.web.service.AiDetectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v2.1.2 报错回归：「触发 AI 模型下载报错（readeraimodel 不可用之类）」。
 *
 * <p>根因：前端 POST 带字符串 body 但未设 {@code Content-Type: application/json}，
 * fetch 默认 text/plain，Spring {@code @RequestBody} 抛
 * {@code HttpMediaTypeNotSupportedException}（HTTP 415），被 GlobalExceptionHandler
 * 兜底后把原始异常文案直接弹给用户。本测试锁定两层：</p>
 * <ol>
 *   <li>控制器层：无 Content-Type 必须得到 415（防止有人误以为接口本身坏了），
 *       带 JSON 头则正常受理；</li>
 *   <li>服务层：下载链路闭环——本地 HttpServer 供真实模型文件，触发下载后
 *       modelState 应在超时内变为 ready 且文件落在数据目录（等价于用户在 NAS 上
 *       点「下载模型」到「模型已就绪」的完整过程）。</li>
 * </ol>
 */
class AiModelDownloadWebTest {

    @TempDir
    Path tmp;

    private static HttpServer modelServer;
    private static int modelPort;

    @BeforeAll
    static void startModelServer() throws Exception {
        // 用仓库根 models/ 下的真实模型文件模拟远端下载源
        Path model = Path.of("models", "ssd_mobilenet_v1_10.onnx");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(model),
                "仓库根 models/ 下缺少模型文件，跳过下载闭环测试");
        byte[] bytes = Files.readAllBytes(model);
        modelServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        modelServer.createContext("/model.onnx", ex -> {
            ex.getResponseHeaders().add("Content-Length", String.valueOf(bytes.length));
            ex.sendResponseHeaders(200, bytes.length);
            try (var out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        modelServer.start();
        modelPort = modelServer.getAddress().getPort();
    }

    @AfterAll
    static void stopModelServer() {
        if (modelServer != null) {
            modelServer.stop(0);
        }
    }

    /** standalone MockMvc + 真实 AiDetectService（临时 DataDir），覆盖 415 回归与受理路径。 */
    private MockMvc mvc(AiDetectService svc, AppSettingsStore settings) {
        SettingsController controller = new SettingsController(
                null, null, null, settings, null, null,
                new ObjectMapper(), svc, null, null, null, null, null, null, null);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingContentTypeIsRejected() throws Exception {
        Path dataDir = tmp.resolve("data");
        Files.createDirectories(dataDir.resolve("models"));
        System.setProperty("ainvr.data.dir", dataDir.toString());
        DataDir.resetForTest();
        try {
            AppSettingsStore settings = new AppSettingsStore(dataDir);
            AiDetectService svc = new AiDetectService(settings, new EventStore(tmp.resolve("events")));
            MockMvc mvc = mvc(svc, settings);
            // 复现用户报错：与旧版前端行为一致（字符串 body、未设 Content-Type）。
            // v2.2.0 起由专门处理器转成 415 + 可读中文文案（此前是 500 + 英文原始异常）
            mvc.perform(post("/api/settings/ai-model/download")
                            .content("{\"url\":\"http://127.0.0.1:" + modelPort + "/model.onnx\"}"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.error").value(
                            "请求体格式不正确（Content-Type 需为 application/json），请升级前端后重试"));
        } finally {
            System.clearProperty("ainvr.data.dir");
            DataDir.resetForTest();
        }
    }

    @Test
    void downloadEndsReadyWithJsonContentType() throws Exception {
        Path dataDir = tmp.resolve("data2");
        Files.createDirectories(dataDir.resolve("models"));
        System.setProperty("ainvr.data.dir", dataDir.toString());
        DataDir.resetForTest();
        try {
            AppSettingsStore settings = new AppSettingsStore(dataDir);
            AiDetectService svc = new AiDetectService(settings, new EventStore(tmp.resolve("events")));
            MockMvc mvc = mvc(svc, settings);
            // 正确的请求形态（修复后的前端行为）：受理并返回聚合设置
            mvc.perform(post("/api/settings/ai-model/download")
                            .contentType("application/json")
                            .content("{\"url\":\"http://127.0.0.1:" + modelPort + "/model.onnx\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.ai.modelState").isNotEmpty());
            // 下载闭环：模型文件落地且状态就绪（轮询状态，最多 20 秒）
            Path downloaded = dataDir.resolve("models").resolve("ssd_mobilenet_v1_10.onnx");
            long deadline = System.currentTimeMillis() + 20_000;
            String state = null;
            while (System.currentTimeMillis() < deadline) {
                state = String.valueOf(svc.status().get("modelState"));
                if ("ready".equals(state)) {
                    break;
                }
                Thread.sleep(200);
            }
            assertEquals("ready", state, "下载后 modelState 应为 ready");
            assertTrue(Files.isRegularFile(downloaded), "模型应下载到数据目录 models/ 下");
            assertEquals(Files.size(Path.of("models", "ssd_mobilenet_v1_10.onnx")),
                    Files.size(downloaded), "下载文件大小应与源一致");
        } finally {
            System.clearProperty("ainvr.data.dir");
            DataDir.resetForTest();
        }
    }
}
