package com.ainvr.miss;

import java.util.Set;

/**
 * MISS 命令字与媒体 codec 标识（1:1 对照 go2rtc pkg/xiaomi/miss/client.go）。
 */
public final class MissCommands {

    private MissCommands() {
    }

    /**
     * 步进式云台（fixed-step）机型：命令载荷必须是 {@code {"operation":1..4}}，
     * 而不是通用机型的 {@code {"direction":"left","speed":5}}。
     * 型号表来自 lalmax-nvr internal/xiaomi/motor.go（MotorProfileForModel）。
     */
    private static final Set<String> FIXED_STEP_MOTOR_MODELS = Set.of(
            "isa.camera.hlc8a",          // CW400
            "isa.camera.500dh",          // CW500
            "chuangmi.camera.026c05",    // MJSXJ10CM
            "chuangmi.camera.029a02",    // 米家 360 2K
            "xiaomi.camera.c01a01",
            "xiaomi.camera.082ac1");     // 已实测验证

    /**
     * 按型号给出应当使用的云台命令格式：{@code operation}（步进式）或
     * {@code direction}（通用连续转动）。dafang 私有 TUTK 协议本项目不支持，
     * 归到 direction 并由设备自行忽略。
     */
    public static String motorModeFor(String model) {
        return FIXED_STEP_MOTOR_MODELS.contains(model == null ? "" : model)
                ? "operation" : "direction";
    }

    public static final int CMD_AUTH_REQ = 0x100;
    public static final int CMD_AUTH_RES = 0x101;
    public static final int CMD_VIDEO_START = 0x102;
    public static final int CMD_VIDEO_STOP = 0x103;
    public static final int CMD_AUDIO_START = 0x104;
    public static final int CMD_AUDIO_STOP = 0x105;
    public static final int CMD_SPEAKER_START_REQ = 0x106;
    public static final int CMD_SPEAKER_START_RES = 0x107;
    public static final int CMD_SPEAKER_STOP = 0x108;
    public static final int CMD_STREAM_CTRL_REQ = 0x109;
    public static final int CMD_STREAM_CTRL_RES = 0x10A;
    public static final int CMD_GET_AUDIO_FORMAT_REQ = 0x10B;
    public static final int CMD_GET_AUDIO_FORMAT_RES = 0x10C;
    public static final int CMD_PLAYBACK_REQ = 0x10D;
    public static final int CMD_PLAYBACK_RES = 0x10E;
    public static final int CMD_DEV_INFO_REQ = 0x110;
    public static final int CMD_DEV_INFO_RES = 0x111;
    public static final int CMD_MOTOR_REQ = 0x112;
    public static final int CMD_MOTOR_RES = 0x113;
    public static final int CMD_ENCODED = 0x1001;

    public static final int CODEC_H264 = 4;
    public static final int CODEC_H265 = 5;
    public static final int CODEC_PCM = 1024;
    public static final int CODEC_PCMU = 1026;
    public static final int CODEC_PCMA = 1027;
    public static final int CODEC_OPUS = 1032;

    /** 这些老/特殊型号时间戳不可靠或走 dafang 私有命令；本项目不支持 dafang，仅保留时间戳特例。 */
    public static boolean usesLocalTimestamp(String model) {
        return "isa.camera.df3".equals(model)
                || "isa.camera.isc5c1".equals(model)
                || "loock.cateye.v02".equals(model);
    }

    /**
     * 默认质量档：绝大多数 2020 后型号为 2=hd；046c04/72ac1 需用 3。
     * hlc8（CW400 室外机）在 HD 档位下实测鉴权通过却收不到任何媒体包，先按 1=sd 取流。
     */
    public static String defaultQuality(String model) {
        if ("chuangmi.camera.046c04".equals(model) || "chuangmi.camera.72ac1".equals(model)) {
            return "3";
        }
        if ("isa.camera.hlc8".equals(model)) {
            return "1";
        }
        return "2";
    }

    /**
     * 对讲上行编码：绝大多数机型为 G.711 A-law 8kHz（{@link #CODEC_PCMA}，每 40ms 320 字节）；
     * {@code isa.camera.hlc6} 与 dafang/xiaofang 老机型为 16bit 线性 PCM（{@link #CODEC_PCM}，
     * 每 40ms 640 字节）——dafang 私有协议本项目不支持，仅 hlc6 走线性 PCM；
     * {@code chuangmi.camera.72ac1} 需要 OPUS（go2rtc SpeakerCodec 实测），暂不支持其上行。
     */
    public static int speakerCodecFor(String model) {
        return "isa.camera.hlc6".equals(model) ? CODEC_PCM : CODEC_PCMA;
    }

    /** 该机型对讲上行是否为 16bit 线性 PCM（前端按此选择采样编码方式）。 */
    public static boolean speakerLinearPcm(String model) {
        return "isa.camera.hlc6".equals(model);
    }

    /** 该机型是否支持对讲上行（72ac1 需要 OPUS 帧拼接，暂不支持）。 */
    public static boolean speakerSupported(String model) {
        return !"chuangmi.camera.72ac1".equals(model);
    }

    /**
     * 按 DID / 全局系统属性覆盖取流档位，便于现场在不改包的情况下试参数：
     * {@code -Dinvr.quality.<did>=0|1|2|3} 或 {@code -Dinvr.quality=1}。
     */
    public static String qualityFor(String did, String model) {
        String perDevice = did == null ? null : System.getProperty("invr.quality." + did);
        if (isValidQuality(perDevice)) {
            return perDevice;
        }
        String global = System.getProperty("invr.quality");
        if (isValidQuality(global)) {
            return global;
        }
        return defaultQuality(model);
    }

    private static boolean isValidQuality(String v) {
        return v != null && v.length() == 1 && v.charAt(0) >= '0' && v.charAt(0) <= '3';
    }
}
