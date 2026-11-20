package com.ainvr.miss;

import java.util.Set;

/**
 * 双摄机型（两枚镜头）支持。
 *
 * <p>协议层：VideoStart（0x102）对第二镜头使用 {@code {"videoquality":-1,"videoquality2":q,
 * "enableaudio":a}} 载荷（见 {@link MissClient#startMedia} channel="2"），
 * 1:1 对照 go2rtc xiaomi 源的 {@code channel=2} 参数。</p>
 *
 * <p>应用层采用「虚拟设备」方案：双摄机型在设备目录中追加一条 DID 后缀为 {@code #2} 的
 * 虚拟摄像头条目，预览 / 录像 / 回放 / 快照 / 事件等所有下游按普通摄像头工作，
 * 无需感知镜头差异；两条镜头各自独立占用一条上游 MISS 会话。</p>
 */
public final class DualLens {

    private DualLens() {
    }

    /** 第二镜头虚拟设备 DID 后缀。 */
    public static final String SUFFIX = "#2";

    /**
     * 已实测确认支持第二镜头的机型：
     * <ul>
     *   <li>{@code isa.camera.hlmax}：小米双摄系列（社区 go2rtc 实测）；</li>
     *   <li>{@code xiaomi.camera.082ac1}：go2rtc 官方 README 验证机型。</li>
     * </ul>
     * 其他机型可在设置里手动开启（部分固件会以空流应答，关闭开关即可恢复）。
     */
    private static final Set<String> KNOWN_MODELS = Set.of(
            "isa.camera.hlmax",
            "xiaomi.camera.082ac1");

    /** 该型号是否为已知的双摄机型。 */
    public static boolean knownDualLensModel(String model) {
        return KNOWN_MODELS.contains(model == null ? "" : model);
    }

    /** 该 DID 是否为第二镜头虚拟设备。 */
    public static boolean isSecondary(String did) {
        return did != null && did.endsWith(SUFFIX);
    }

    /** 剥离第二镜头后缀返回真实设备 DID；非第二镜头 DID 原样返回。 */
    public static String baseDid(String did) {
        if (isSecondary(did)) {
            return did.substring(0, did.length() - SUFFIX.length());
        }
        return did;
    }

    /** 为真实设备生成第二镜头虚拟 DID。 */
    public static String secondaryDid(String baseDid) {
        return baseDid + SUFFIX;
    }
}
