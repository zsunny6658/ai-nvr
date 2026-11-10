package com.invr.web.service;

import com.invr.cloud.DeviceDiscovery;
import com.invr.cloud.MiCloudApi;
import com.invr.cloud.MiCloudUnauthorizedException;
import com.invr.hub.StreamHub;
import com.invr.model.CameraDevice;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 当前账号下摄像头目录（Web 单例）。登录/手动刷新时从米家云拉取并缓存，
 * 供 RTSP / HLS / 录像按 DID 解析设备（model、局域网 IP、在线状态）。
 *
 * <p>v1.1.9：云端列表刷新后识别「离线→在线」和「局域网 IP 变化」并唤醒对应取流，
 * 避免正在预览的窗口还卡在最长 5 分钟的熔断退避里。</p>
 */
@Component
public class DeviceCatalog {

    private static final Logger log = LoggerFactory.getLogger(DeviceCatalog.class);

    /** 后台自动刷新间隔（秒）：用于让在线/离线状态自动更新，不必等手动刷新。 */
    private static final long AUTO_REFRESH_SECONDS = 60;

    private final SessionHolder sessionHolder;
    private final StreamHub streamHub;
    private volatile List<CameraDevice> devices = List.of();
    private final ScheduledExecutorService scheduler;

    public DeviceCatalog(SessionHolder sessionHolder, StreamHub streamHub) {
        this.sessionHolder = sessionHolder;
        this.streamHub = streamHub;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "device-catalog-refresh");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::safeRefresh,
                AUTO_REFRESH_SECONDS, AUTO_REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    /** 后台任务：仅在已登录时刷新，任何异常都静默保留上一份缓存。 */
    private void safeRefresh() {
        try {
            if (sessionHolder.isLoggedIn() && !devices.isEmpty()) {
                refresh();
            }
        } catch (Exception e) {
            log.debug("后台刷新设备列表失败，保留旧缓存：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 强制从云端刷新设备列表；401 时自动续期并重试一次。 */
    public List<CameraDevice> refresh() {
        var session = sessionHolder.require();
        List<CameraDevice> previous = devices;
        List<CameraDevice> fresh;
        try {
            fresh = new DeviceDiscovery(new MiCloudApi(session)).listCameras();
        } catch (MiCloudUnauthorizedException e) {
            log.info("设备列表会话过期，自动续期后重试");
            var renewed = sessionHolder.renew();
            fresh = new DeviceDiscovery(new MiCloudApi(renewed)).listCameras();
        }
        devices = fresh;
        notifyTransitions(previous, fresh);
        return fresh;
    }

    /** 云端状态的两种关键跃变，都要求立即丢弃旧会话重新协商。 */
    private void notifyTransitions(List<CameraDevice> before, List<CameraDevice> after) {
        Map<String, CameraDevice> old = new HashMap<>();
        for (CameraDevice c : before) {
            old.put(c.did(), c);
        }
        for (CameraDevice now : after) {
            CameraDevice was = old.get(now.did());
            if (was == null) {
                // 首次出现 / 新绑定设备：本来就没有上游会话，无需唤醒
                continue;
            }
            if (!was.online() && now.online()) {
                log.info("设备 {} 由离线转为在线，立即唤醒取流", now.did());
                streamHub.wakeup(now.did(), "米家云：离线→在线", true);
            } else if (!Objects.equals(was.localIp(), now.localIp())
                    && now.localIp() != null && !now.localIp().isBlank()) {
                log.info("设备 {} 局域网 IP 由 {} 变为 {}，立即唤醒取流",
                        now.did(), was.localIp(), now.localIp());
                streamHub.wakeup(now.did(), "云端局域网 IP 变化", true);
            }
        }
    }

    public List<CameraDevice> all() {
        return devices;
    }

    public Optional<CameraDevice> find(String did) {
        return devices.stream().filter(c -> c.did().equals(did)).findFirst();
    }

    /** 找到则返回，否则刷新一次再找（处理刚登录尚未拉列表的情况）。 */
    public CameraDevice findOrRefresh(String did) {
        return find(did).orElseGet(() ->
                refresh().stream().filter(c -> c.did().equals(did)).findFirst().orElse(null));
    }
}
