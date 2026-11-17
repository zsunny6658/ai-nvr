package com.ainvr.hub;

/**
 * 云台命令与设备应答的异步快照。
 *
 * <p>v1.2.1 起云台命令发出即返回，不再在请求线程里阻塞等待 0x113。前端在命令返回后
 * 延时查询 capabilities 接口，用 {@link #fresh()} 判断这条应答是否发生在最近一次命令之后，
 * 从而既能毫秒级反馈「已发出」，又保留 v1.2.0 引入的「设备是否应答」诊断能力。</p>
 *
 * @param reply     最近一次 0x113 应答明文，设备不回应时为 null
 * @param replyAt   应答到达时刻（毫秒），0 表示从未收到
 * @param commandAt 最近一次 0x112 命令发出时刻（毫秒），0 表示本会话尚未发过命令
 */
public record MotorReply(String reply, long replyAt, long commandAt) {

    private static final long FRESH_WINDOW_MS = 8_000L;

    public static MotorReply none() {
        return new MotorReply(null, 0L, 0L);
    }

    /** 应答是否属于最近一次命令，且仍在有效时间窗内。 */
    public boolean fresh() {
        return fresh(System.currentTimeMillis());
    }

    public boolean fresh(long now) {
        return reply != null && !reply.isBlank()
                && commandAt > 0
                && replyAt >= commandAt - 100L
                && now - replyAt <= FRESH_WINDOW_MS;
    }

    public boolean hasReply() {
        return reply != null && !reply.isBlank();
    }
}
