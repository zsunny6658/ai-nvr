package com.ainvr.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 运动侦测的排除区域（v1.2.19，P0-3 检测区域 ROI）。
 *
 * <p>坐标为<strong>画面宽高的归一化比例</strong>（0～1），与显示分辨率无关：前端在最近快照上
 * 拖拽框选，保存后任何分辨率下语义一致。{@code (x, y)} 为矩形左上角，{@code (w, h)} 为宽高。</p>
 *
 * <p><b>与 8×8 感知哈希的映射</b>：MotionDetector 把整幅画面缩成 8×8 灰度块做感知哈希，
 * 每个块对应画面的 1/64 区域，块 {@code (bx, by)} 的中心点是 {@code ((bx+0.5)/8, (by+0.5)/8)}。
 * 排除矩形的集合映射为一个 64 位掩码：块中心落在任一矩形内 → 该块不参与帧差比较
 * （掩码位为 0）。帧差评分改为「掩码内不一致位数 / 掩码位数」，排除区里的树影、马路、
 * 常亮屏幕就不再贡献变化分数。</p>
 *
 * <p>夹取规则：坐标裁剪到 0～1，宽高下限 0.02（约 1/8 画面，避免肉眼不可见的碎块）、
 * 上限 1；超过 8 个矩形只保留前 8 个（防误操作把画面切得太碎）。矩形总面积超过 90% 时
 * 由调用方拒绝保存（等于把侦测关掉，应去关开关而不是框满画面）。</p>
 *
 * @param x 矩形左上角 X（0～1）
 * @param y 矩形左上角 Y（0～1）
 * @param w 矩形宽（0.02～1）
 * @param h 矩形高（0.02～1）
 */
public record MotionZone(double x, double y, double w, double h) {

    /** 每设备最多排除矩形数。 */
    public static final int MAX_ZONES = 8;

    public MotionZone {
        x = clamp01(x);
        y = clamp01(y);
        w = clamp(w, 0.02, 1.0);
        h = clamp(h, 0.02, 1.0);
        // 右/下越界的矩形整体裁回画面内：先裁位置，再保证宽高不越过 1
        if (x + w > 1.0) {
            w = 1.0 - x < 0.02 ? 0.02 : 1.0 - x;
            if (x > 0.98) { x = 0.98; w = 0.02; }
        }
        if (y + h > 1.0) {
            h = 1.0 - y < 0.02 ? 0.02 : 1.0 - y;
            if (y > 0.98) { y = 0.98; h = 0.02; }
        }
    }

    /** 块中心 (cx, cy)（已归一化）是否落在本矩形内。 */
    public boolean contains(double cx, double cy) {
        return cx >= x && cx <= x + w && cy >= y && cy <= y + h;
    }

    /** 矩形面积（归一化）。 */
    public double area() {
        return w * h;
    }

    /**
     * 由排除矩形集合构建 8×8 感知哈希掩码：返回值第 {@code i} 位（0～63，i = by*8 + bx，
     * 与 MotionDetector.hash 的位移顺序一致）为 1 表示该块<strong>参与</strong>比较，
     * 为 0 表示被排除。无矩形时返回 {@code ~0L}（全参与）。
     */
    public static long mask64(List<MotionZone> zones) {
        long mask = 0L;
        for (int by = 0; by < 8; by++) {
            for (int bx = 0; bx < 8; bx++) {
                double cx = (bx + 0.5) / 8.0;
                double cy = (by + 0.5) / 8.0;
                boolean excluded = false;
                if (zones != null) {
                    for (MotionZone z : zones) {
                        if (z.contains(cx, cy)) {
                            excluded = true;
                            break;
                        }
                    }
                }
                if (!excluded) {
                    mask |= 1L << (by * 8 + bx);
                }
            }
        }
        return mask;
    }

    /** 防御性拷贝 + 数量截断；null 安全。 */
    public static List<MotionZone> sanitize(List<MotionZone> zones) {
        if (zones == null || zones.isEmpty()) {
            return List.of();
        }
        List<MotionZone> out = new ArrayList<>();
        for (MotionZone z : zones) {
            if (z == null) {
                continue;
            }
            out.add(new MotionZone(z.x(), z.y(), z.w(), z.h()));
            if (out.size() >= MAX_ZONES) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** 矩形总面积；用于拒绝「框满全画面」。 */
    public static double totalArea(List<MotionZone> zones) {
        double sum = 0;
        if (zones != null) {
            for (MotionZone z : zones) {
                sum += z.area();
            }
        }
        return sum;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
