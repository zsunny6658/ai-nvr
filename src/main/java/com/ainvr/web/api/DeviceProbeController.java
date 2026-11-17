package com.ainvr.web.api;

import com.ainvr.web.service.StreamProbeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 设备自检接口：{@code GET /api/devices/{did}/probe}（浅检）与
 * {@code ?deep=true}（额外取流探测编码 / 分辨率 / 帧率）。
 *
 * <p>位于 {@code /api/**}，自动沿用访问密码门禁。浅检零副作用；深检会临时订阅取流，
 * 详见 {@link StreamProbeService}。</p>
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceProbeController {

    private final StreamProbeService probes;

    public DeviceProbeController(StreamProbeService probes) {
        this.probes = probes;
    }

    @GetMapping("/{did}/probe")
    public Map<String, Object> probe(@PathVariable String did,
                                     @RequestParam(name = "deep", defaultValue = "false") boolean deep) {
        return probes.probe(did, deep);
    }
}
