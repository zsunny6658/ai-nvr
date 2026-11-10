package com.invr.cloud;

import com.invr.hub.MissOpener;
import com.invr.miss.MissClient;
import com.invr.miss.MissCommands;
import com.invr.model.CameraDevice;
import com.invr.model.P2pConnectParams;
import com.invr.store.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 小米云端实现的上游开启器：每次（重）连接都重新 miss_get_vendor 协商，
 * 再建立 CS2/MISS 并 VideoStart。供 StreamHub 的自动重连循环调用。
 *
 * <p>v1.1.9：设备与取流档位改为「每次重连时动态取」。</p>
 * <ul>
 *   <li>设备来自 {@link Supplier}（读设备目录缓存），否则设备重新上线换了局域网 IP，
 *       重连仍在用订阅建立那一刻的旧对象，永远连不上；</li>
 *   <li>档位来自 Web 设置，改完立即重连即可生效，无需重启 / 重新打包。</li>
 * </ul>
 *
 * <p>协商遇到会话过期（401）时用 passToken 自动续期一次并重试；
 * 门铃/电池类（.cateye.）连接前先唤醒。</p>
 */
public final class CloudMissOpener implements MissOpener {

    private static final Logger log = LoggerFactory.getLogger(CloudMissOpener.class);

    /** 连续「设备接受会话但零媒体包」的次数，驱动取流档位自动降级。 */
    private final AtomicInteger noFrameStreak = new AtomicInteger();

    private final SessionStore sessionStore;
    private final AtomicReference<LoginSession> sessionRef;
    private final Supplier<CameraDevice> deviceSupplier;
    private final String channel;
    private final Supplier<String> qualitySupplier;
    private final String audio;

    /** CLI 落盘等一次性场景使用的固定设备/固定档位重载（保持旧调用方兼容）。 */
    public CloudMissOpener(SessionStore sessionStore,
                           AtomicReference<LoginSession> sessionRef,
                           CameraDevice device,
                           String channel,
                           String quality,
                           String audio) {
        this(sessionStore, sessionRef, () -> device, channel, () -> quality, audio);
    }

    public CloudMissOpener(SessionStore sessionStore,
                           AtomicReference<LoginSession> sessionRef,
                           Supplier<CameraDevice> deviceSupplier,
                           String channel,
                           Supplier<String> qualitySupplier,
                           String audio) {
        this.sessionStore = sessionStore;
        this.sessionRef = sessionRef;
        this.deviceSupplier = deviceSupplier;
        this.channel = channel == null ? "" : channel;
        this.qualitySupplier = qualitySupplier;
        this.audio = audio == null ? "1" : audio;
    }

    @Override
    public MissClient open() throws IOException {
        // 关键：每次重连都取最新设备（云端每 60s 刷新，IP / 在线状态可能已变）
        CameraDevice device = deviceSupplier.get();
        P2pConnectParams params = negotiateWithRecovery(device);

        if (device.model() != null && device.model().contains(".cateye.")) {
            try {
                new DeviceDiscovery(new MiCloudApi(sessionRef.get())).wakeup(device.did());
                Thread.sleep(1_000);
            } catch (Exception e) {
                // 唤醒失败不阻断，继续尝试取流
            }
        }

        MissClient client = MissClient.connect(params, "");
        boolean ok = false;
        try {
            String configured = qualitySupplier == null ? null : qualitySupplier.get();
            String q;
            String a = audio;
            if (isFixedQuality(configured)) {
                // 用户明确指定档位：尊重选择，不再被自动降级悄悄改写
                q = configured;
                log.info("[{}] VideoStart 参数 channel={} quality={} audio={}（Web 固定档位）",
                        device.did(), channel, q, a);
            } else {
                q = MissCommands.defaultQuality(device.model());
                int streak = noFrameStreak.get();
                // 设备鉴权通过却持续零媒体包时，优先怀疑档位/音频不被支持：
                // HD -> SD -> auto，并逐步关掉音频，避免因参数不兼容永远拿不到首帧。
                if (streak >= 8) {
                    q = "0";
                    a = "0";
                } else if (streak >= 4) {
                    q = "1";
                    a = "0";
                } else if (streak >= 2) {
                    q = "1";
                }
                log.info("[{}] VideoStart 参数 channel={} quality={} audio={}（自动档，连续零帧 {} 次）",
                        device.did(), channel, q, a, streak);
            }
            client.startMedia(channel, q, a);
            ok = true;
            return client;
        } finally {
            if (!ok) {
                client.close();
            }
        }
    }

    /** 允许用户在 Web 端固定的档位；null/空/default 表示按型号自动并允许降级。 */
    static boolean isFixedQuality(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        String s = v.trim().toLowerCase();
        return s.equals("0") || s.equals("1") || s.equals("2") || s.equals("3")
                || s.equals("sd") || s.equals("hd") || s.equals("auto");
    }

    @Override
    public void noteNoFrame() {
        noFrameStreak.incrementAndGet();
    }

    @Override
    public void noteFrame() {
        noFrameStreak.set(0);
    }

    private P2pConnectParams negotiateWithRecovery(CameraDevice device) {
        try {
            return new VendorNegotiator(new MiCloudApi(sessionRef.get())).negotiate(device);
        } catch (MiCloudUnauthorizedException e) {
            LoginSession old = sessionRef.get();
            LoginSession renewed = new MiCloudClientImpl()
                    .loginWithToken(old.userId(), old.passToken(), old.region());
            sessionStore.save(renewed);
            sessionRef.set(renewed);
            return new VendorNegotiator(new MiCloudApi(renewed)).negotiate(device);
        }
    }
}
