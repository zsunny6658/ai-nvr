package com.ainvr.codec;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 残缺帧判定回归：样本取自线上 i-nvr 事件快照（2026-11-14），正常画面与 P 帧残差各一组。
 * 判据阈值按这批真实数据统计校准，改动 {@link FrameQuality} 阈值必须同时通过本测试。
 */
class FrameQualityTest {

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = FrameQualityTest.class.getResourceAsStream("/frames/" + name)) {
            if (in == null) {
                throw new IllegalStateException("缺少测试样本：" + name);
            }
            return in.readAllBytes();
        }
    }

    @Test
    void detectsRealResidualFrames() throws Exception {
        // 大片纯灰 + 中间一小块半透明人影（P 帧缺参考帧）
        assertTrue(FrameQuality.isBroken(resource("broken-residual.jpg")));
        // 翻转/灰度错乱但仍有部分画面的边界样本
        assertTrue(FrameQuality.isBroken(resource("broken-mild.jpg")));
        // 完全无信号的纯色灰屏
        assertTrue(FrameQuality.isBroken(resource("blank-gray.jpg")));
    }

    @Test
    void keepsRealNormalFrames() throws Exception {
        assertFalse(FrameQuality.isBroken(resource("normal-corridor.jpg")));
    }

    @Test
    void detectsSyntheticGrayWithResidualPatch() throws Exception {
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        // 真实残缺帧的灰基底是 YUV 128 → sRGB 灰度 128（经 TYPE_BYTE_GRAY.getRGB 的
        // gamma 校正后读作约 189，判据窗口即据此校准），不是直接写 189。
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, 640, 360);
        Random r = new Random(42);
        for (int i = 0; i < 900; i++) {
            int v = 60 + r.nextInt(140);
            g.setColor(new Color(v, v, v));
            g.fillRect(180 + r.nextInt(140), 140 + r.nextInt(140), 2, 2);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        assertTrue(FrameQuality.isBroken(out.toByteArray()));
    }

    @Test
    void keepsSyntheticTexturedScene() throws Exception {
        BufferedImage img = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(7);
        for (int y = 0; y < 360; y++) {
            for (int x = 0; x < 640; x++) {
                int v = Math.max(0, Math.min(255,
                        110 + (x / 12) * 9 + (y / 9) * 5 + r.nextInt(38) - 19));
                img.setRGB(x, y, new Color(v, v, v).getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        assertFalse(FrameQuality.isBroken(out.toByteArray()));
    }

    @Test
    void brokenJpegIsConservativelyAllowed() {
        assertFalse(FrameQuality.isBroken((byte[]) null));
        assertFalse(FrameQuality.isBroken(new byte[0]));
        assertFalse(FrameQuality.isBroken(new byte[]{1, 2, 3, 4}));
        assertFalse(FrameQuality.isBroken((BufferedImage) null));
    }
}
