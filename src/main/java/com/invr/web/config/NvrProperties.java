package com.invr.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * iNvr Web 服务配置（application.yml 中 {@code invr.*}）。
 */
@ConfigurationProperties(prefix = "invr")
public class NvrProperties {

    private int rtspPort = 8554;
    private String recordingDir = "recordings";
    private long recordSegmentSec = 120;
    /** 录像保留天数：0 表示永久保留，不自动清理；>0 时超出该天数的分段自动删除。 */
    private int recordRetentionDays = 0;
    /**
     * 内嵌 RTSP 下行访问令牌。非空时，所有 RTSP 请求须带上 {@code ?t=<token>} 且值匹配才放行；
     * 留空（默认）则不鉴权。建议配合 HTTPS 反向代理使用，避免令牌在链路上明文暴露。
     */
    private String rtspToken = "";
    /**
     * opt-in（默认关闭）：米家会话加密密钥由“访问密码”派生（PBKDF2），不再生成与密文同目录的
     * master.key。开启后重启需先用访问密码解锁才能恢复米家会话（安全性更高）；旧版 master.key
     * 加密的会话在切换后需重新登录一次米家账号。
     */
    private boolean sessionKeyFromPassword = false;
    private final Hls hls = new Hls();
    private final Share share = new Share();

    public int getRtspPort() {
        return rtspPort;
    }

    public void setRtspPort(int rtspPort) {
        this.rtspPort = rtspPort;
    }

    public String getRtspToken() {
        return rtspToken;
    }

    public void setRtspToken(String rtspToken) {
        this.rtspToken = rtspToken == null ? "" : rtspToken;
    }

    public boolean isSessionKeyFromPassword() {
        return sessionKeyFromPassword;
    }

    public void setSessionKeyFromPassword(boolean sessionKeyFromPassword) {
        this.sessionKeyFromPassword = sessionKeyFromPassword;
    }

    public String getRecordingDir() {
        return recordingDir;
    }

    public void setRecordingDir(String recordingDir) {
        this.recordingDir = recordingDir;
    }

    public long getRecordSegmentSec() {
        return recordSegmentSec;
    }

    public void setRecordSegmentSec(long recordSegmentSec) {
        this.recordSegmentSec = recordSegmentSec;
    }

    public int getRecordRetentionDays() {
        return recordRetentionDays;
    }

    public void setRecordRetentionDays(int recordRetentionDays) {
        this.recordRetentionDays = recordRetentionDays;
    }

    public Hls getHls() {
        return hls;
    }

    public Share getShare() {
        return share;
    }

    public static class Hls {
        private int targetSec = 2;
        private int window = 6;
        private int idleSec = 25;
        private boolean audio = true;
        /** 是否对在线摄像头预热并保持常驻（点击即播，但会长期占用上游连接，默认关闭）。 */
        private boolean prewarm = false;

        public int getTargetSec() {
            return targetSec;
        }

        public void setTargetSec(int targetSec) {
            this.targetSec = targetSec;
        }

        public int getWindow() {
            return window;
        }

        public void setWindow(int window) {
            this.window = window;
        }

        public int getIdleSec() {
            return idleSec;
        }

        public void setIdleSec(int idleSec) {
            this.idleSec = idleSec;
        }

        public boolean isAudio() {
            return audio;
        }

        public void setAudio(boolean audio) {
            this.audio = audio;
        }

        public boolean isPrewarm() {
            return prewarm;
        }

        public void setPrewarm(boolean prewarm) {
            this.prewarm = prewarm;
        }
    }

    /** 共享看板相关配置（application.yml 中 invr.share.*）。 */
    public static class Share {
        /** 事件画面持续秒数，到期广播 end。 */
        private long eventDurationSec = 60;
        /** SSE 心跳周期（秒）。 */
        private long sseHeartbeatSec = 15;
        /** 展示页配置轮询周期（秒）。 */
        private long configPollSec = 60;
        /** 大屏兜底重载周期（秒），0 关闭。 */
        private long pageReloadSec = 3600;

        public long getEventDurationSec() {
            return eventDurationSec;
        }

        public void setEventDurationSec(long eventDurationSec) {
            this.eventDurationSec = eventDurationSec;
        }

        public long getSseHeartbeatSec() {
            return sseHeartbeatSec;
        }

        public void setSseHeartbeatSec(long sseHeartbeatSec) {
            this.sseHeartbeatSec = sseHeartbeatSec;
        }

        public long getConfigPollSec() {
            return configPollSec;
        }

        public void setConfigPollSec(long configPollSec) {
            this.configPollSec = configPollSec;
        }

        public long getPageReloadSec() {
            return pageReloadSec;
        }

        public void setPageReloadSec(long pageReloadSec) {
            this.pageReloadSec = pageReloadSec;
        }
    }
}
