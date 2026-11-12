package com.invr.web.api;

import com.invr.web.service.MotionRecordingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 自动录像状态机的运行态观测（只读）；位于 /api/** 自动受访问密码门禁保护。 */
@RestController
@RequestMapping("/api/motion/recording")
public class MotionRecordingController {

    private final MotionRecordingService service;

    public MotionRecordingController(MotionRecordingService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> status() {
        return service.status();
    }
}
