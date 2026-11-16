package com.invr.store;

import java.util.List;
import java.util.Locale;

/**
 * 事件通知推送设置（对应 settings.json 的 {@code notify} 子对象，v1.2.19 新增）。
 *
 * <p>画面变动 / 设备离线 / 重连等事件产生时，可同时走两个出向通道：</p>
 * <ul>
 *   <li><strong>Webhook</strong>：向 {@code webhookUrl} POST JSON 事件载荷；配置了
 *       {@code webhookSecret} 时附带 {@code X-invr-Signature} 头
 *       （HMAC-SHA256 十六进制，密钥对原始请求体计算），便于接收端验签；</li>
 *   <li><strong>SMTP 邮件</strong>：内置轻量 SMTP 客户端（JDK 原生 Socket/SSLSocket，
 *       不引入第三方依赖），465 端口默认隐式 TLS，587 默认 STARTTLS，支持 AUTH LOGIN。</li>
 * </ul>
 *
 * <p>安全提示：SMTP 授权码以明文保存在数据目录 settings.json 中（与访问密码派生参数同盘），
 * 请确保 NAS 数据目录访问权限。字段在紧凑构造器里统一夹到安全区间。</p>
 *
 * @param enabled       总开关（关闭时不发送任何通知）
 * @param types         推送的事件类型白名单（motion/offline/reconnect/record），空=全部
 * @param quietStartMin 静默时段开始（当日分钟数 0-1439，与结束相等表示不启用静默）
 * @param quietEndMin   静默时段结束（支持跨午夜，如 1320→420 表示 22:00～次日 07:00）
 * @param webhookUrl    Webhook 地址（空=不启用该通道）
 * @param webhookSecret Webhook 验签密钥（可空）
 * @param mailEnabled   邮件通道开关
 * @param smtpHost      SMTP 服务器
 * @param smtpPort      SMTP 端口（465 隐式 TLS / 587 STARTTLS / 25 明文）
 * @param smtpUser      认证用户（空=不认证）
 * @param smtpPass      认证密码 / 授权码
 * @param mailFrom      发件人（默认取 smtpUser）
 * @param mailTo        收件人（逗号分隔多个）
 */
public record NotificationSettings(
        boolean enabled,
        List<String> types,
        int quietStartMin,
        int quietEndMin,
        String webhookUrl,
        String webhookSecret,
        boolean mailEnabled,
        String smtpHost,
        int smtpPort,
        boolean smtpSsl,
        String smtpUser,
        String smtpPass,
        String mailFrom,
        String mailTo) {

    public static final NotificationSettings DEFAULTS =
            new NotificationSettings(false, List.of("motion", "offline"),
                    -1, -1, "", "", false, "", 465, true, "", "", "", "");

    /** 兼容无静默时段的旧调用形态（测试便利）。 */
    public NotificationSettings(boolean enabled, List<String> types,
                                String webhookUrl, String webhookSecret,
                                boolean mailEnabled, String smtpHost, int smtpPort,
                                boolean smtpSsl, String smtpUser, String smtpPass,
                                String mailFrom, String mailTo) {
        this(enabled, types, -1, -1, webhookUrl, webhookSecret,
                mailEnabled, smtpHost, smtpPort, smtpSsl, smtpUser, smtpPass, mailFrom, mailTo);
    }

    public NotificationSettings {
        if (types == null) {
            types = List.of();
        } else {
            types = types.stream().filter(t -> t != null && !t.isBlank())
                    .map(t -> t.trim().toLowerCase(Locale.ROOT))
                    .distinct().toList();
        }
        quietStartMin = clampMin(quietStartMin);
        quietEndMin = clampMin(quietEndMin);
        webhookUrl = trimToEmpty(webhookUrl);
        webhookSecret = trimToEmpty(webhookSecret);
        smtpHost = trimToEmpty(smtpHost);
        smtpPort = (int) clamp(smtpPort, 1, 65535);
        smtpUser = trimToEmpty(smtpUser);
        smtpPass = smtpPass == null ? "" : smtpPass.trim();
        mailFrom = trimToEmpty(mailFrom);
        mailTo = trimToEmpty(mailTo);
    }

    private static int clampMin(int v) {
        if (v < 0 || v > 1439) {
            return -1; // -1 = 未配置
        }
        return v;
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    /** 是否配置了 Webhook 通道。 */
    public boolean webhookReady() {
        return !webhookUrl.isBlank();
    }

    /** 是否配置了邮件通道。 */
    public boolean mailReady() {
        return mailEnabled && !smtpHost.isBlank() && !mailTo.isBlank();
    }

    /**
     * 静默时段判定；start==end（含都为 -1）表示不启用。跨午夜窗口（start&gt;end）取并集。
     */
    public boolean inQuietWindow(int minuteOfDay) {
        if (quietStartMin < 0 || quietEndMin < 0 || quietStartMin == quietEndMin) {
            return false;
        }
        int m = ((minuteOfDay % 1440) + 1440) % 1440;
        if (quietStartMin < quietEndMin) {
            return m >= quietStartMin && m < quietEndMin;
        }
        return m >= quietStartMin || m < quietEndMin;
    }

    /** 事件类型是否命中推送白名单（空列表=全部推送）。 */
    public boolean coversType(String type) {
        if (types.isEmpty()) {
            return true;
        }
        return type != null && types.contains(type.trim().toLowerCase(Locale.ROOT));
    }
}
