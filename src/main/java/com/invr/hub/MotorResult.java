package com.invr.hub;

/**
 * 一次云台命令的执行结果。
 *
 * <p>区分三种状态：没有可用会话（sent=false, reply=null）、命令已送达但设备不理
 * （sent=true, reply=null）、设备明确回了 0x113 应答（sent=true, reply!=null）。
 * 早期版本只返回 boolean，界面点击后没有任何反馈，用户无法判断是「没接上」、
 * 「命令没发出去」还是「设备不支持云台」。</p>
 */
public record MotorResult(boolean sent, String deviceReply, String mode) {

    public static MotorResult noSession(String mode) {
        return new MotorResult(false, null, mode);
    }

    public boolean deviceReplied() {
        return deviceReply != null && !deviceReply.isBlank();
    }
}
