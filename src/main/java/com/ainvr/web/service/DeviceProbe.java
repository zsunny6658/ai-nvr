package com.ainvr.web.service;

import com.ainvr.hub.StreamHub;
import com.ainvr.model.CameraDevice;
import com.ainvr.store.HostResolver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 局域网 CS2 可达性探测：独立判断「这个 IP 上此刻有没有一台能取流的摄像头」。
 *
 * <p>背景（v1.1.8 实测）：米家云的 {@code isOnline} 只是云端心跳，设备换了 IP、
 * 休眠、或 P2P 服务没起来时，云端仍可能长期报「在线」并保留旧 {@code localip}。
 * 只依赖云端状态，设备恢复后要么用旧 IP 重连，要么卡在熔断退避里。</p>
 *
 * <p>这里每 30 秒对每台已知设备发一个 4 字节 CS2 {@code LAN_SEARCH}（UDP 32108），
 * 收到任意回应即判定 P2P 服务可用。状态由「不可达」变为「可达」时唤醒取流；
 * 探测不建立会话、不占用摄像头并发通道。</p>
 */
@Component
public class DeviceProbe {

    private static final Logger log = LoggerFactory.getLogger(DeviceProbe.class);

    /** CS2 LAN_SEARCH：magic 0xF1 + msg 0x30 + 长度 0。 */
    private static final byte[] LAN_SEARCH = {(byte) 0xF1, 0x30, 0x00, 0x00};
    private static final int CS2_PORT = 32108;
    private static final int REPLY_TIMEOUT_MS = 700;
    private static final long INTERVAL_SEC = 30;

    private final DeviceCatalog catalog;
    private final StreamHub hub;
    private final HostResolver hostResolver = new HostResolver();
    private final ConcurrentHashMap<String, Boolean> lastReachable = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public DeviceProbe(DeviceCatalog catalog, StreamHub hub) {
        this.catalog = catalog;
        this.hub = hub;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "device-cs2-probe");
            t.setDaemon(true);
            return t;
        });
        // 启动 20 秒后首探（避开应用刚启动时的协商高峰），之后每 30 秒一次
        this.scheduler.scheduleAtFixedRate(this::safeProbe, 20, INTERVAL_SEC, TimeUnit.SECONDS);
    }

    private void safeProbe() {
        try {
            for (CameraDevice d : catalog.all()) {
                String ip = hostResolver.resolve(d);
                if (ip == null) {
                    continue;
                }
                boolean reachable = probe(ip);
                Boolean prev = lastReachable.put(d.did(), reachable);
                if (Boolean.FALSE.equals(prev) && reachable) {
                    log.info("设备 {}（{}）CS2 服务恢复可达，立即唤醒取流", d.did(), ip);
                    hub.wakeup(d.did(), "局域网 CS2 服务恢复可达", true);
                } else if (prev == null) {
                    log.debug("设备 {}（{}）CS2 可达性首次探测：{}", d.did(), ip,
                            reachable ? "可达" : "不可达");
                }
            }
        } catch (Exception e) {
            log.debug("局域网可达性探测异常（忽略）：{}", e.toString());
        }
    }

    /** 单播 CS2 LAN_SEARCH，收到任意回包即认为该设备的 P2P 服务在线。 */
    static boolean probe(String ip) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(REPLY_TIMEOUT_MS);
            InetAddress addr = InetAddress.getByName(ip);
            for (int i = 0; i < 2; i++) {
                socket.send(new DatagramPacket(LAN_SEARCH, LAN_SEARCH.length, addr, CS2_PORT));
                byte[] buf = new byte[256];
                DatagramPacket reply = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(reply);
                    return true;
                } catch (SocketTimeoutException ignore) {
                    // 端口关闭时 Java 也可能抛 PortUnreachableException，统一视为不可达
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** 供状态接口读取最近一次探测结果；null 表示尚未探测。 */
    public Boolean reachable(String did) {
        return lastReachable.get(did);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
