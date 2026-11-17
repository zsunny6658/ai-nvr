package com.ainvr.codec;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * 解码帧质量判定：识别「大片纯灰基底 + 局部一小块残差画面」的残缺帧。
 *
 * <p><b>成因</b>：H.264/H.265 的 P 帧只携带相对参考帧的残差。若解码器在收到 IDR 之前
 * 就开始解码（新订阅首包不是关键帧、上游重连后 flush 了参考帧、或传输丢包打断参考链），
 * FFmpeg 的错误隐藏会把未覆盖的宏块填成 YUV 128，于是输出一帧「大片均匀灰 + 运动区域
 * 半透明人影」。这既不能作为兼容画面展示，也会因与正常帧差异极大被运动侦测误判成事件。</p>
 *
 * <p><b>判据（线上样本实测校准）</b>：把画面缩到 32×32 灰度，按 4×4 共 64 个块统计：
 * 「灰基底块」= 块均值 175~202 且块内标准差 &lt; 6；「纹理块」= 块内标准差 &gt; 10。
 * 注意 {@code TYPE_BYTE_GRAY.getRGB()} 会做 gamma 校正：真实残缺帧的 YUV 128 灰基底
 * 在 raster 中是 128，经本链路读出约 189，故窗口取 175~202（与运动侦测直接用 raster
 * 时的 128 是同一批灰帧）。当灰基底块占比 ≥ 20% 且纹理块占比 &lt; 75%
 * 时判为残缺帧。线上 40 张正常画面灰基底占比全部为 0%、纹理块 ≥ 87.5%；20 张残缺帧
 * 灰基底 26.6%~100%、纹理块 ≤ 67.2%，两组无交叠。附加纹理条件是为了避免把正对灰墙、
 * 白墙的正常静态画面误杀。</p>
 *
 * <p>解析失败一律返回 false（保守放行），绝不因质量判定本身异常而吞掉正常画面。</p>
 */
public final class FrameQuality {

    private static final int GRID = 32;
    private static final int BLOCK = 4;
    private static final int BLOCKS = (GRID / BLOCK) * (GRID / BLOCK);

    private static final double GRAY_MIN = 175.0;
    private static final double GRAY_MAX = 202.0;
    private static final double GRAY_BLOCK_STD = 6.0;
    private static final double MIN_GRAY_RATIO = 0.20;

    private static final double TEXTURED_STD = 10.0;
    private static final double MAX_TEXTURED_RATIO = 0.75;

    private FrameQuality() {
    }

    /** 判定一张 JPEG 是否为残缺帧；无法解码时返回 false。 */
    public static boolean isBroken(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) {
            return false;
        }
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            return img != null && isBroken(img);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 判定已解码图像是否为残缺帧（供运动侦测复用同一次解码结果）。 */
    public static boolean isBroken(BufferedImage src) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
            return false;
        }
        BufferedImage gray = new BufferedImage(GRID, GRID, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, GRID, GRID, null);
        } finally {
            g.dispose();
        }

        int[] px = new int[GRID * GRID];
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                px[y * GRID + x] = gray.getRGB(x, y) & 0xFF;
            }
        }

        int grayBlocks = 0;
        int texturedBlocks = 0;
        for (int by = 0; by < GRID; by += BLOCK) {
            for (int bx = 0; bx < GRID; bx += BLOCK) {
                double sum = 0.0;
                for (int y = by; y < by + BLOCK; y++) {
                    for (int x = bx; x < bx + BLOCK; x++) {
                        sum += px[y * GRID + x];
                    }
                }
                double mean = sum / (BLOCK * BLOCK);
                double var = 0.0;
                for (int y = by; y < by + BLOCK; y++) {
                    for (int x = bx; x < bx + BLOCK; x++) {
                        double d = px[y * GRID + x] - mean;
                        var += d * d;
                    }
                }
                double std = Math.sqrt(var / (BLOCK * BLOCK));
                if (mean >= GRAY_MIN && mean <= GRAY_MAX && std < GRAY_BLOCK_STD) {
                    grayBlocks++;
                }
                if (std > TEXTURED_STD) {
                    texturedBlocks++;
                }
            }
        }
        double grayRatio = (double) grayBlocks / BLOCKS;
        double texturedRatio = (double) texturedBlocks / BLOCKS;
        return grayRatio >= MIN_GRAY_RATIO && texturedRatio < MAX_TEXTURED_RATIO;
    }
}
