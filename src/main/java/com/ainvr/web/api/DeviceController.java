package com.ainvr.web.api;

import com.ainvr.model.CameraDevice;
import com.ainvr.rtsp.RtspServer;
import com.ainvr.store.HostResolver;
import com.ainvr.web.config.NvrProperties;
import com.ainvr.web.service.DeviceCatalog;
import com.ainvr.web.service.HlsService;
import com.ainvr.web.service.RecordingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final HostResolver hostResolver = new HostResolver();

    public DeviceController(DeviceCatalog catalog, RecordingService recordingService,
                            HlsService hlsService, NvrProperties props, RtspServer rtspServer) {
        this.catalog = catalog;
        this.recordingService = recordingService;
        this.hlsService = hlsService;
        this.props = props;
        this.rtspServer = rtspServer;
    }

    @GetMapping("/devices")
    public Map<String, Object> devices(@RequestParam(name = "refresh", defaultValue = "false") boolean refresh,
                                       HttpServletRequest request) {
        List<CameraDevice> devices = (refresh || catalog.all().isEmpty())
                ? catalog.refresh() : catalog.all();
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
}
