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
     * 对讲上行编码（对照 go2rtc SpeakerCodec / lalmax SpeakerCodec，以及米家 miot-plugin-sdk
     * 的 per-model codec 选择）：
     * <ul>
     *   <li>2020 前老机型（dafang/xiaofang/{@code isa.camera.hlc6}）为 16bit 线性 PCM
     *       （{@link #CODEC_PCM}，每 40ms 640 字节，8kHz）；</li>
     *   <li>{@code chuangmi.camera.72ac1} 需要 OPUS 40ms 帧（第十六轮起支持，WebCodecs 编码）；</li>
     *   <li><strong>第二十九轮定版</strong>：OPUS 机型 = {@code 039a01}（小米2 云台版，
     *       真机 OPUS 正常/PCMA 变声）、{@code 079ac1}（小米摄像头 4，2025 新机型，
     *       PCMA 无声）与 {@code isa.camera.hlc8}（CW400，第二十七轮 OPUS 真机正常
     *       ——go2rtc {@code SpeakerCodec()} 将其归默认 PCMA 与实测不符，以真机为准；
     *       其第二十六轮 PCMA「杂音」实为当时 DataChannel 无界队列/堆压力损坏帧流）；</li>
     *   <li>其余机型 G.711 A-law 8kHz（{@link #CODEC_PCMA}，每 40ms 320 字节）。</li>
     * </ul>
     * 可用系统属性按 DID 覆盖现场试参：{@code -Dinvr.talkcodec.<did>=opus|pcma|pcm}。
     */
    public static int speakerCodecFor(String model) {
        return speakerCodecFor(model, null);
    }

    /** 同上，支持按 DID 的系统属性覆盖（值不区分大小写：opus/pcma/pcm）。 */
    public static int speakerCodecFor(String model, String did) {
        String ov = did == null ? null : System.getProperty("invr.talkcodec." + did);
        if (ov == null) {
            ov = System.getProperty("invr.talkcodec");
        }
        if (ov != null) {
            ov = ov.trim().toLowerCase();
            if ("opus".equals(ov)) {
                return CODEC_OPUS;
            }
            if ("pcma".equals(ov)) {
                return CODEC_PCMA;
            }
            if ("pcm".equals(ov)) {
                return CODEC_PCM;
            }
        }
        if ("isa.camera.hlc6".equals(model)) {
            return CODEC_PCM;
        }
        // OPUS 机型（第二十九轮定版，均经真机验证）：72ac1（第十六轮）、
        // 039a01 小米2云台版（第二十七轮，OPUS 正常/PCMA 变声）、
        // 079ac1 小米摄像头4（2025 新机型，PCMA 无声，与 039a01 同代）、
        // hlc8 CW400（第二十七轮 OPUS 真机正常——go2rtc SpeakerCodec() 将其归入
        // 默认 PCMA 与实测不符，以真机为准；其第二十六轮 PCMA「杂音」实为当时
        // DataChannel 无界队列/OOM 损坏帧流所致）。
        if ("isa.camera.hlc8".equals(model) || "chuangmi.camera.039a01".equals(model)
                || "chuangmi.camera.079ac1".equals(model)
                || "chuangmi.camera.72ac1".equals(model)) {
            return CODEC_OPUS;
        }
        return CODEC_PCMA;
    }

    /** 该机型对讲上行是否为 16bit 线性 PCM（前端按此选择采样编码方式）。 */
    public static boolean speakerLinearPcm(String model) {
        return speakerLinearPcm(model, null);
    }

    /** 同上，支持按 DID 覆盖。 */
    public static boolean speakerLinearPcm(String model, String did) {
        return speakerCodecFor(model, did) == CODEC_PCM;
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
