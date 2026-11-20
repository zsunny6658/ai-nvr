package com.ainvr.web.service;

import com.ainvr.model.CameraDevice;
import com.ainvr.store.AppSettingsStore;
import com.ainvr.store.EventStore;
import com.ainvr.store.EventStore.AlarmEvent;
import com.ainvr.web.service.DeviceCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Home Assistant 接入（v2.3.1）：经 MQTT 发布 AI 事件与实时快照，并用 HA discovery
 * 自动注册实体——用户只需在 HA 里配置一次 MQTT broker，aiNVR 的摄像头即全部出现。
 *
 * <p>设计目标：优于「摄像头直连 HA」。直连方案里 HA 只拿到画面流，分析能力要另配
 * Frigate/Double Take；本集成让 HA 直接消费 aiNVR 的结构化识别结果：</p>
 * <ul>
 *   <li>每台摄像头一个 HA <strong>设备</strong>，自动注册 4 个二进制传感器
 *       （motion / person / vehicle / pet，{@code off_delay} 自动回落）+
 *       1 个画面实体（MQTT camera，快照经 {@link LiveFrameService} 周期发布）；</li>
 *   <li>事件流：{@code {base}/events/{did}} 发布 JSON 事件载荷（含类型/置信度/检测框），
 *       自动化直接按对象类型写条件；</li>
 *   <li>可用性：全局 {@code {base}/status}（遗嘱 retained offline）+ 每摄像头
 *       {@code {base}/cam/{did}/availability}（设备离线联动，retained）；</li>
 *   <li>重连自动重发：broker 断连恢复后 discovery 配置与状态全量重发（retained 幂等）。</li>
 * </ul>
 *
 * <p>可靠性约束与 {@link NotifyService} 一致：事件监听路径绝不阻塞、绝不抛异常；
 * MQTT 发送失败只记日志。会话密码仅存本地 settings.json，接口下发时只给
 * {@code mqttPassSet} 布尔位。</p>
 */
@Service
public class HaMqttService {

    private static final Logger log = LoggerFactory.getLogger(HaMqttService.class);
    /** AI 事件 → 二进制传感器的类型集合（与 AI 精化类型一致）。 */
    private static final List<String> SENSOR_TYPES = List.of("motion", "person", "vehicle", "pet");

    private final AppSettingsStore settingsStore;
    private final EventStore events;
    private final DeviceCatalog catalog;
    private final LiveFrameService liveFrames;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile MqttClient client;
    /** 当前连接实际使用的配置（reload 时对比，避免无变化重连）。 */
    private volatile Map<String, Object> activeCfg;
    private volatile boolean connected;
    private volatile String lastError = "";
    private volatile long lastConnectAt;
    private ScheduledExecutorService snapshotSched;
    private int snapshotTickSec = 15;

    public HaMqttService(AppSettingsStore settingsStore, EventStore events,
                         DeviceCatalog catalog, LiveFrameService liveFrames) {
        this.settingsStore = settingsStore;
        this.events = events;
        this.catalog = catalog;
        this.liveFrames = liveFrames;
    }

    @PostConstruct
    void init() {
        // 事件监听常驻注册（未连接时内部短路），设置变更由 SettingsController 调 reload
        events.addListener(this::onEventAdded);
        events.addRefineListener(this::onEventRefined);
        if ((Boolean) settingsStore.mqtt().get("enabled")) {
            start();
        }
    }

    @PreDestroy
    void shutdown() {
        stop();
    }

    // ===================== 生命周期 =====================

    /** 设置保存后调用：停旧连接，按新配置重启（未启用则仅停止）。 */
    public synchronized void reload() {
        stop();
        if ((Boolean) settingsStore.mqtt().get("enabled")) {
            start();
        }
    }

    private synchronized void start() {
        Map<String, Object> cfg = settingsStore.mqtt();
        String host = String.valueOf(cfg.get("host"));
        if (!(Boolean) cfg.get("enabled") || host.isEmpty()) {
            return;
        }
        try {
            String url = ((Boolean) cfg.get("tls") ? "ssl://" : "tcp://") + host + ":" + cfg.get("port");
            MqttClient c = new MqttClient(url, "ainvr-" + Long.toHexString(System.nanoTime()),
                    new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setAutomaticReconnect(true);
            o.setConnectionTimeout(8);
            o.setKeepAliveInterval(30);
            o.setMaxInflight(32);
            String user = String.valueOf(cfg.get("username"));
            if (!user.isEmpty()) {
                o.setUserName(user);
            }
            String pass = String.valueOf(cfg.get("password"));
            if (!pass.isEmpty()) {
                o.setPassword(pass.toCharArray());
            }
            String statusTopic = topic(cfg, "status");
            // 遗嘱：异常掉线时 broker 代发 retained offline，HA 实体转入「不可用」
            o.setWill(statusTopic, "offline".getBytes(StandardCharsets.UTF_8), 0, true);

            final Map<String, Object> cfgRef = cfg;
            c.setCallback(new MqttCallbackExtended() {
                @Override
                public void connectComplete(boolean reconnect, String serverURI) {
                    connected = true;
                    lastConnectAt = System.currentTimeMillis();
                    lastError = "";
                    log.info("MQTT 已连接 {}（{}）", serverURI, reconnect ? "自动重连" : "首次");
                    publishAll(cfgRef);
                }

                @Override
                public void connectionLost(Throwable cause) {
                    connected = false;
                    lastError = cause == null ? "连接丢失" : String.valueOf(cause.getMessage());
                    log.warn("MQTT 连接丢失：{}，自动重连中", lastError);
                }

                @Override
                public void messageArrived(String t, org.eclipse.paho.client.mqttv3.MqttMessage m) {
                    // 纯发布端，不订阅
                }

                @Override
                public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken token) {
                    // 忽略
                }
            });
            // client/activeCfg 必须在 connect() 前就位：connectComplete 回调在 connect()
            // 内部同步触发，若引用未赋值，discovery 与状态会被「未连接」守卫静默丢弃
            client = c;
            activeCfg = cfg;
            c.connect(o);
            startSnapshotScheduler(cfg);
            log.info("MQTT 客户端已启动：{}:{}（HA discovery 前缀 {}）",
                    host, cfg.get("port"), cfg.get("discoveryPrefix"));
        } catch (Exception e) {
            lastError = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
            log.warn("MQTT 连接失败：{}", lastError);
        }
    }

    private synchronized void stop() {
        ScheduledExecutorService s = snapshotSched;
        snapshotSched = null;
        if (s != null) {
            s.shutdownNow();
        }
        MqttClient c = client;
        client = null;
        activeCfg = null;
        connected = false;
        if (c != null) {
            try {
                if (c.isConnected()) {
                    c.publish(topic(activeCfgOrStore(), "status"),
                            retained("offline"));
                }
            } catch (Exception e) {
                // 忽略：关闭路径尽力而为
            }
            try {
                c.disconnect();
                c.close(true);
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    private Map<String, Object> activeCfgOrStore() {
        Map<String, Object> c = activeCfg;
        return c != null ? c : settingsStore.mqtt();
    }

    // ===================== 状态（设置页展示） =====================

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connected", connected);
        m.put("lastError", lastError);
        m.put("lastConnectAt", lastConnectAt);
        if (connected) {
            List<CameraDevice> devs = catalog.all();
            m.put("deviceCount", devs.size());
            m.put("entityCount", devs.size() * 5);
        }
        return m;
    }

    /** 连接测试（设置页「测试连接」按钮）：临时客户端直连后立即断开，返回耗时。 */
    public Map<String, Object> testConnection(Map<String, Object> cfg) {
        Map<String, Object> out = new LinkedHashMap<>();
        String host = String.valueOf(cfg.getOrDefault("host", "")).trim();
        if (host.isEmpty()) {
            out.put("ok", false);
            out.put("error", "broker 地址不能为空");
            return out;
        }
        long t0 = System.currentTimeMillis();
        try {
            String url = ((Boolean) cfg.getOrDefault("tls", false) ? "ssl://" : "tcp://")
                    + host + ":" + cfg.getOrDefault("port", 1883);
            MqttClient c = new MqttClient(url, "ainvr-test-" + Long.toHexString(System.nanoTime()),
                    new MemoryPersistence());
            MqttConnectOptions o = new MqttConnectOptions();
            o.setConnectionTimeout(6);
            String user = String.valueOf(cfg.getOrDefault("username", ""));
            if (!user.isEmpty()) {
                o.setUserName(user);
            }
            String pass = String.valueOf(cfg.getOrDefault("password", ""));
            if (!pass.isEmpty()) {
                o.setPassword(pass.toCharArray());
            }
            c.connect(o);
            c.disconnect();
            c.close(true);
            out.put("ok", true);
            out.put("latencyMs", System.currentTimeMillis() - t0);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage() == null ? String.valueOf(e) : e.getMessage());
        }
        return out;
    }

    // ===================== 事件入口 =====================

    /** 新增事件：motion → 传感器 ON；offline/reconnect → 摄像头可用性联动；全部 → 事件流。 */
    private void onEventAdded(AlarmEvent e) {
        try {
            if (!connected) {
                return;
            }
            Map<String, Object> cfg = activeCfg;
            if (cfg == null) {
                return;
            }
            publishEvent(cfg, e);
            String type = e.type() == null ? "" : e.type();
            if (SENSOR_TYPES.contains(type)) {
                publishSensor(cfg, e.did(), type, "ON");
            } else if ("offline".equals(type) || "reconnect".equals(type)) {
                publishString(topic(cfg, "cam/" + e.did() + "/availability"),
                        "offline".equals(type) ? "offline" : "online", true);
            }
        } catch (Exception ex) {
            log.debug("MQTT 事件发布失败（不影响主链路）：{}", ex.getMessage());
        }
    }

    /** AI 精化（person/vehicle/pet）：传感器 ON + 事件流 + 立即推一张实时快照。 */
    private void onEventRefined(AlarmEvent e) {
        try {
            if (!connected) {
                return;
            }
            Map<String, Object> cfg = activeCfg;
            if (cfg == null) {
                return;
            }
            publishEvent(cfg, e);
            String type = e.type() == null ? "" : e.type();
            if (SENSOR_TYPES.contains(type)) {
                publishSensor(cfg, e.did(), type, "ON");
            }
            publishLiveFrame(cfg, e.did());
        } catch (Exception ex) {
            log.debug("MQTT 精化发布失败（不影响主链路）：{}", ex.getMessage());
        }
    }

    /** 发布二进制传感器状态（ON/OFF；OFF 态由 HA off_delay 自动回落，这里只在事件时发 ON 与初始 OFF）。 */
    private void publishSensor(Map<String, Object> cfg, String did, String type, String state) throws Exception {
        publishString(topic(cfg, "cam/" + did + "/" + type), state, true);
    }

    private void publishEvent(Map<String, Object> cfg, AlarmEvent e) throws Exception {        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", e.id());
        p.put("did", e.did());
        p.put("device", e.deviceName());
        p.put("type", e.type());
        p.put("title", e.title());
        p.put("score", e.score());
        p.put("ts", e.createdAt());
        if (e.detections() != null && !e.detections().isEmpty()) {
            p.put("detections", e.detections());
        }
        publishString(topic(cfg, "events/" + e.did()),
                mapper.writeValueAsString(p), false);
    }

    // ===================== discovery 与周期快照 =====================

    /** 连接建立（含自动重连）后全量重发：状态 + 每摄像头 discovery 配置 + 初始传感器态 + 可用性。 */
    private void publishAll(Map<String, Object> cfg) {
        try {
            publishString(topic(cfg, "status"), "online", true);
            List<CameraDevice> devs = catalog.all();
            for (CameraDevice d : devs) {
                publishDiscovery(cfg, d);
                publishString(topic(cfg, "cam/" + d.did() + "/availability"),
                        d.online() ? "online" : "offline", true);
                for (String type : SENSOR_TYPES) {
                    publishString(topic(cfg, "cam/" + d.did() + "/" + type), "OFF", true);
                }
            }
            log.info("MQTT discovery 已发布：{} 台摄像头 × 5 实体", devs.size());
        } catch (Exception e) {
            log.warn("MQTT discovery 发布失败：{}", e.getMessage());
        }
    }

    private void publishDiscovery(Map<String, Object> cfg, CameraDevice d) throws Exception {
        String prefix = String.valueOf(cfg.get("discoveryPrefix"));
        String did = d.did();
        Map<String, Object> device = new LinkedHashMap<>();
        device.put("identifiers", List.of("ainvr_" + did));
        device.put("name", d.name() == null || d.name().isBlank() ? "aiNVR " + did : d.name());
        device.put("manufacturer", "小米");
        device.put("model", d.model() == null || d.model().isBlank() ? "小米摄像头" : d.model());

        List<Map<String, Object>> availability = List.of(
                Map.of("topic", topic(cfg, "status")),
                Map.of("topic", topic(cfg, "cam/" + did + "/availability")));

        // 4 个二进制传感器：motion / person / vehicle / pet（off_delay 自动回落 OFF）
        String[][] sensors = {
                {"motion", "画面变动"},
                {"person", "人形检测"},
                {"vehicle", "车辆检测"},
                {"pet", "宠物检测"}};
        for (String[] s : sensors) {
            Map<String, Object> conf = baseConfig(prefix, "binary_sensor",
                    "ainvr_" + did + "_" + s[0], s[1], device, availability);
            conf.put("state_topic", topic(cfg, "cam/" + did + "/" + s[0]));
            conf.put("payload_on", "ON");
            conf.put("payload_off", "OFF");
            conf.put("off_delay", ((Number) cfg.get("offDelaySec")).intValue());
            publishJson(prefix + "/binary_sensor/ainvr_" + did + "_" + s[0] + "/config", conf);
        }

        // 画面实体：MQTT camera（周期快照 + AI 事件即时快照）
        Map<String, Object> cam = baseConfig(prefix, "camera",
                "ainvr_" + did + "_live",
                d.name() == null || d.name().isBlank() ? "aiNVR " + did : d.name(), device, availability);
        cam.put("topic", topic(cfg, "cam/" + did + "/image"));
        cam.put("image_encoding", "binary");
        publishJson(prefix + "/camera/ainvr_" + did + "_live/config", cam);
    }

    private Map<String, Object> baseConfig(String prefix, String domain, String uniqueId,
                                           String name, Map<String, Object> device,
                                           List<Map<String, Object>> availability) {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("name", name);
        conf.put("unique_id", uniqueId);
        conf.put("availability", availability);
        conf.put("availability_mode", "all");
        conf.put("device", device);
        return conf;
    }

    /** 周期快照发布：默认每 30s 一张（0 = 关闭），画面无流时静默跳过。 */
    private void startSnapshotScheduler(Map<String, Object> cfg) {
        int interval = ((Number) cfg.getOrDefault("snapshotIntervalSec", 30)).intValue();
        if (interval <= 0) {
            return;
        }
        snapshotTickSec = Math.max(5, Math.min(interval, 60));
        snapshotSched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mqtt-snapshot");
            t.setDaemon(true);
            return t;
        });
        snapshotSched.scheduleAtFixedRate(() -> {
            try {
                if (!connected) {
                    return;
                }
                Map<String, Object> c = activeCfg;
                if (c == null) {
                    return;
                }
                long now = System.currentTimeMillis();
                long last = lastSnapshotAt;
                if (now - last < interval * 1000L - 500) {
                    return;
                }
                lastSnapshotAt = now;
                List<CameraDevice> devs = catalog.all();
                for (CameraDevice d : devs) {
                    publishLiveFrame(c, d.did());
                }
            } catch (Exception e) {
                log.debug("MQTT 快照周期发布失败：{}", e.getMessage());
            }
        }, snapshotTickSec, snapshotTickSec, TimeUnit.SECONDS);
    }

    private volatile long lastSnapshotAt;

    private void publishLiveFrame(Map<String, Object> cfg, String did) {
        try {
            byte[] jpeg = liveFrames.frame(did);
            if (jpeg != null && jpeg.length > 0 && client != null && client.isConnected()) {
                publishBytes(topic(cfg, "cam/" + did + "/image"), jpeg, false);
            }
        } catch (Exception e) {
            log.debug("MQTT 快照发布失败 {}：{}", did, e.getMessage());
        }
    }

    // ===================== 底层发送 =====================

    private String topic(Map<String, Object> cfg, String suffix) {
        return cfg.get("baseTopic") + "/" + suffix;
    }

    private MqttMessage retained(String payload) {
        return msg(payload.getBytes(StandardCharsets.UTF_8), true);
    }

    private MqttMessage msg(byte[] payload, boolean retained) {
        MqttMessage m = new MqttMessage(payload);
        m.setQos(0);
        m.setRetained(retained);
        return m;
    }

    private void publishString(String t, String payload, boolean retained) throws Exception {
        publishBytes(t, payload.getBytes(StandardCharsets.UTF_8), retained);
    }

    private void publishBytes(String t, byte[] payload, boolean retained) throws Exception {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            return;
        }
        c.publish(t, msg(payload, retained));
    }

    private void publishJson(String t, Map<String, Object> conf) throws Exception {
        publishString(t, mapper.writeValueAsString(conf), true);
    }
}
