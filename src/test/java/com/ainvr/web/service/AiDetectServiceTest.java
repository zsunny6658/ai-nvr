package com.ainvr.web.service;

import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.DataDir;
import com.ainvr.store.EventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.1.0 A2 本地 AI 检测：模型加载 + 推理管线冒烟（真实跑一次 ONNX 推理）。
 * 断言只要求「管线可用、返回结构正确」，不依赖具体画面内容（合成图不一定检出目标）。
 *
 * <p>v2.1.1 起模型不再内置进 jar（改为按需下载到数据目录），本测试把仓库根
 * {@code models/} 下的模型文件复制到临时 DataDir，走真实加载路径。</p>
 */
class AiDetectServiceTest {

    @TempDir
    Path tmp;

    private static final Path REPO_MODEL =
            Path.of("models", "ssd_mobilenet_v1_10.onnx");

    /** 把模型放到临时数据目录并让 DataDir 指向它（测试结束自动清理）。 */
    private void prepareModelInDataDir() throws Exception {
        assertTrue(java.nio.file.Files.isRegularFile(REPO_MODEL),
                "仓库根 models/ 下缺少模型文件（推理冒烟需要）");
        Path dataDir = tmp.resolve("data");
        java.nio.file.Files.createDirectories(dataDir.resolve("models"));
        java.nio.file.Files.copy(REPO_MODEL, dataDir.resolve("models")
                .resolve("ssd_mobilenet_v1_10.onnx"));
        System.setProperty("ainvr.data.dir", dataDir.toString());
        DataDir.resetForTest();
    }

    private static byte[] jpegOf(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void detectRunsOnBuiltInModelAndReturnsWellFormedDetections() throws Exception {
        prepareModelInDataDir();
        try {
            AppSettingsStore settings = new AppSettingsStore(tmp);
            EventStore events = new EventStore(tmp.resolve("events"));
            AiDetectService svc = new AiDetectService(settings, events);

            // 合成 640x360 图像（纯色渐变 + 矩形），不预设检出结果，仅验证管线
            BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            for (int x = 0; x < 640; x++) {
                g.setColor(new Color(x % 255, (x * 2) % 255, (x * 3) % 255));
                g.drawLine(x, 0, x, 360);
            }
            g.setColor(Color.WHITE);
            g.fillRect(200, 100, 120, 200);
            g.dispose();

            List<Map<String, Object>> dets = svc.detect(jpegOf(img));
            assertNotNull(dets);
            for (Map<String, Object> d : dets) {
                // 结构校验：type 限于 person/vehicle/pet，分数与框坐标在合理范围
                assertTrue(d.get("type") instanceof String t
                        && List.of("person", "vehicle", "pet").contains(t));
                double score = (Double) d.get("score");
                assertTrue(score >= 0.5);
                @SuppressWarnings("unchecked")
                List<Number> box = (List<Number>) d.get("box");
                assertEquals(4, box.size());
                for (Number v : box) {
                    assertTrue(v.doubleValue() >= 0.0 && v.doubleValue() <= 1.0);
                }
            }
        } finally {
            System.clearProperty("ainvr.data.dir");
            DataDir.resetForTest();
        }
    }

    @Test
    void disabledSettingsSkipDetection() throws Exception {
        AppSettingsStore settings = new AppSettingsStore(tmp);
        settings.saveAi(false, null);
        EventStore events = new EventStore(tmp.resolve("events"));
        AiDetectService svc = new AiDetectService(settings, events);

        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        List<Map<String, Object>> dets = svc.detect(jpegOf(img));
        assertEquals(List.of(), dets);
    }

    @Test
    void statusReportsConfiguration() {
        AppSettingsStore settings = new AppSettingsStore(tmp);
        EventStore events = new EventStore(tmp.resolve("events"));
        AiDetectService svc = new AiDetectService(settings, events);
        Map<String, Object> st = svc.status();
        assertEquals(true, st.get("enabled"));
        assertEquals(0.5, ((Number) st.get("minScore")).doubleValue(), 1e-9);
        // v2.1.1 模型状态字段：状态取值合法（旧构建残留内置模型时可为 ready），并给出默认下载地址
        assertTrue(List.of("missing", "ready").contains(st.get("modelState")),
                "modelState 取值异常：" + st.get("modelState"));
        assertNotNull(st.get("defaultModelUrl"));
        assertNotNull(st.get("modelPath"));
    }
}
