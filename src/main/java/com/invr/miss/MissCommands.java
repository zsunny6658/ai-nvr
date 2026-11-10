package com.invr.miss;

/**
 * MISS 命令字与媒体 codec 标识（1:1 对照 go2rtc pkg/xiaomi/miss/client.go）。
 */
public final class MissCommands {

    private MissCommands() {
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
