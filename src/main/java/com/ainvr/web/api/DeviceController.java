package com.ainvr.web.api;

import com.ainvr.model.CameraDevice;
import com.ainvr.rtsp.RtspServer;
import com.ainvr.store.HostResolver;
import com.ainvr.web.config.NvrProperties;
import com.ainvr.web.service.DeviceCatalog;
import com.ainvr.web.service.DeviceProbe;
import com.ainvr.web.service.HlsService;
import com.ainvr.web.service.MiotPropsService;
import com.ainvr.web.service.MiotSleepService;
import com.ainvr.web.service.RecordingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 摄像头设备列表与实时状态（预览 / 录像 / 下行地址）。
 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceCatalog catalog;
    private final RecordingService recordingService;
    private final HlsService hlsService;
    private final NvrProperties props;
    private final RtspServer rtspServer;
    private final DeviceProbe deviceProbe;
    private final MiotSleepService sleepService;
    private final MiotPropsService miotProps;
    private final HostResolver hostResolver = new HostResolver();

    public DeviceController(DeviceCatalog catalog, RecordingService recordingService,
                            HlsService hlsService, NvrProperties props, RtspServer rtspServer,
                            DeviceProbe deviceProbe, MiotSleepService sleepService,
                            MiotPropsService miotProps) {
        this.catalog = catalog;
        this.recordingService = recordingService;
        this.hlsService = hlsService;
        this.props = props;
        this.rtspServer = rtspServer;
        this.deviceProbe = deviceProbe;
        this.sleepService = sleepService;
        this.miotProps = miotProps;
    }

    @GetMapping("/devices")
    public Map<String, Object> devices(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh,
                                       HttpServletRequest request) {
        List<CameraDevice> devices = (refresh || catalog.all().isEmpty())
                ? catalog.refresh() : catalog.all();
        // 强制刷新（解锁进页面 / 手动刷新按钮）时同步触发一轮即时休眠探测，
        // 避免 90s 轮询间隙里休眠徽标停留在陈旧状态
        if (refresh) {
            sleepService.kick();
        }
        String host = request.getServerName();
        List<Map<String, Object>> list = devices.stream()
                .map(c -> toMap(c, host))
                .toList();
        return Map.of("devices", list, "count", list.size());
    }

    @GetMapping("/devices/{did}")
    public Map<String, Object> device(@PathVariable String did, HttpServletRequest request) {
        CameraDevice c = catalog.findOrRefresh(did);
        if (c == null) {
            throw ApiException.notFound("设备列表中未找到 did=" + did);
        }
        return toMap(c, request.getServerName());
    }

    private Map<String, Object> toMap(CameraDevice c, String host) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("did", c.did());
        m.put("name", c.name());
        m.put("model", c.model());
        // 显示实际可用于连接的 IP：云端 localip 有效用云端，云端为 0.0.0.0 时回退历史可用 IP
        String effectiveIp = hostResolver.resolve(c);
        m.put("localIp", effectiveIp != null ? effectiveIp : c.localIp());
        m.put("online", c.online());
        // v2.3.1 局域网 CS2 可达性（DeviceProbe 30s 一轮）：米家云 isOnline 只是云端心跳，
        // 摄像头休眠时云端仍报在线，本字段为 false 即「云端在线但局域网不可达（休眠/掉线）」；
        // null 表示尚未探测（如启动后 20 秒内或无可用 IP）。
        m.put("lanReachable", deviceProbe.reachable(c.did()));
        // v2.3.1 休眠状态（MiotSleepService 90s 一轮，MIoT camera_control.on）：
        // 休眠中 CS2 唤醒监听仍会应答 LAN_SEARCH，可达性区分不出休眠，以云端属性为准；
        // true=休眠中（界面显示黄色「休眠」徽标，可点击唤醒），null=尚未探测/固件不支持。
        m.put("sleeping", sleepService.sleeping(c.did()));
        // 探测自证：非 null 表示最近一次属性读取失败（-4006=固件不支持该属性），前端可展示
        m.put("sleepProbeFail", sleepService.probeFailCode(c.did()));
        m.put("recording", recordingService.isRecording(c.did()));
        m.put("previewing", hlsService.isPreviewing(c.did()));
        String rtspUrl = "rtsp://" + host + ":" + rtspServer.getPort() + "/" + c.did();
        // v1.2.22：令牌运行时可在设置页更换，从 RtspServer 取当前值（回落配置文件）
        String tok = rtspServer.getToken() != null ? rtspServer.getToken() : props.getRtspToken();
        if (tok != null && !tok.isEmpty()) {
            rtspUrl += "?t=" + tok;
        }
        m.put("rtspUrl", rtspUrl);
        // 相对路径：配合前端 <base href="./">，直连根路径与 fnOS 网关 /app/{appname}/ 下都能正确解析
        m.put("hlsUrl", "hls/" + c.did() + "/index.m3u8");
        return m;
    }

    /** 唤醒 / 休眠摄像头（MIoT camera_control.on 属性写操作，云端下发）。 */
    @PostMapping("/devices/{did}/sleep/{wake}")
    public Map<String, Object> setSleep(@PathVariable String did, @PathVariable boolean wake) {
        sleepService.setSleep(did, !wake);
        return Map.of("ok", true, "sleeping", !wake);
    }

    /**
     * 实时休眠探测（验证用）：绕过 90s 轮询缓存直接读云端 MIoT 属性。
     * 返回 {did, code, value, sleeping}；code!=0 或 value=null 表示固件不支持该属性
     * （-4006）或读取失败，sleeping 为最近缓存值（可能为 null=未知，界面按在线显示）。
     */
    @GetMapping("/devices/{did}/sleep")
    public Map<String, Object> probeSleep(@PathVariable String did) {
        return sleepService.probeOne(did);
    }

    /**
     * 读取该摄像头全部米家可控属性（夜视/翻转/水印/人形追踪/录制模式/看家助手/存储卡）。
     * 每项 {label, code, value}；code!=0 或 value=null 表示该固件不支持，界面按不支持渲染。
     */
    @GetMapping("/devices/{did}/miot")
    public Map<String, Object> miotProps(@PathVariable String did) {
        return Map.of("did", did, "props", miotProps.readAll(did));
    }

    /** 写单个米家属性（body: {"value": ...}，类型按属性定义：bool / uint8 / uint16）。 */
    public record MiotPropRequest(Object value) {
    }

    @PutMapping("/devices/{did}/miot/{key}")
    public Map<String, Object> setMiotProp(@PathVariable String did, @PathVariable String key,
                                           @RequestBody MiotPropRequest req) {
        if (req == null || req.value() == null) {
            throw ApiException.badRequest("缺少 value");
        }
        miotProps.write(did, key, req.value());
        return Map.of("ok", true);
    }

    /** 重启摄像头（米家 restart-device 动作，云端下发，设备约 30~60s 恢复）。 */
    @PostMapping("/devices/{did}/reboot")
    public Map<String, Object> reboot(@PathVariable String did) {
        miotProps.reboot(did);
        return Map.of("ok", true);
    }
}
