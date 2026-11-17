package com.invr.web.api;

import com.invr.web.service.RecordingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * MP4 分段录像的启停、列表、在线回放 / 下载、删除，以及批量删除 / 打包下载。
 */
@RestController
@RequestMapping("/api")
public class RecordingController {

    private static final Logger log = LoggerFactory.getLogger(RecordingController.class);
    private static final DateTimeFormatter ZIP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final RecordingService recordingService;

    public RecordingController(RecordingService recordingService) {
        this.recordingService = recordingService;
    }

    public record StartRequest(Long segmentSec) {
    }

    @PostMapping("/record/{did}/start")
    public Map<String, Object> start(@PathVariable String did,
                                     @RequestBody(required = false) StartRequest req) {
        Long seg = req == null ? null : req.segmentSec();
        return recordingService.start(did, seg);
    }

    @PostMapping("/record/{did}/stop")
    public Map<String, Object> stop(@PathVariable String did) {
        return recordingService.stop(did);
    }

    @GetMapping("/recordings")
    public List<Map<String, Object>> list(@RequestParam(name = "did", required = false) String did)
            throws IOException {
        return recordingService.list(did);
    }

    /** 时间轴回放：某摄像头某天的全部分段（升序，含开始时间/估算时长/是否录制中）。 */
    @GetMapping("/timeline/{did}")
    public Map<String, Object> timeline(@PathVariable String did,
                                        @RequestParam(name = "date", required = false) String date)
            throws IOException {
        return recordingService.timeline(did, date);
    }

    @GetMapping("/recordings/{name:.+}")
    public ResponseEntity<Resource> playback(@PathVariable String name,
                                             @RequestParam(name = "dl", defaultValue = "false") boolean download) {
        File f = recordingService.resolveFile(name);
        String disposition = (download ? "attachment" : "inline")
                + "; filename*=UTF-8''" + URLEncoder.encode(f.getName(), StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(new FileSystemResource(f));
    }

    @DeleteMapping("/recordings/{name:.+}")
    public Map<String, Object> delete(@PathVariable String name) {
        recordingService.delete(name);
        return Map.of("deleted", name);
    }

    // ===================== 批量操作（v1.2.2） =====================

    /** 批量请求体：names 为录像文件名数组（不是路径）。 */
    public record BatchRequest(List<String> names) {
    }

    /**
     * 批量删除。逐条独立处理，返回 deleted / skipped（录制中）/ failed（含原因），
     * 这样部分失败时前端能如实提示，而不是笼统报「删除失败」。
     */
    @PostMapping("/recordings/batch/delete")
    public Map<String, Object> batchDelete(@RequestBody(required = false) BatchRequest req) {
        return recordingService.deleteBatch(req == null ? null : req.names());
    }

    /**
     * 批量打包为 zip 流式下载。预检先过滤正在写入 / 不存在的文件，并把数量写进响应头，
     * 前端下载完成后可提示「已打包 N 个，跳过 M 个」。
     */
    @PostMapping("/recordings/batch/download")
    public ResponseEntity<StreamingResponseBody> batchDownload(
            @RequestBody(required = false) BatchRequest req) {
        RecordingService.BatchPlan plan = recordingService.planZip(req == null ? null : req.names());
        String filename = "ainvr-recordings-" + LocalDateTime.now().format(ZIP_TIME) + ".zip";
        StreamingResponseBody body = out -> {
            try {
                int n = recordingService.writeZip(plan, out);
                log.info("批量打包下载完成：{} 个分段，跳过 {} 个", n,
                        plan.skippedWriting() + plan.skippedUnavailable());
            } catch (IOException e) {
                // 客户端取消下载时连接已断，记录即可，不向外抛（响应头已写出）
                log.warn("批量打包下载中断：{}", e.toString());
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .header("X-Invr-Zip-Files", String.valueOf(plan.size()))
                .header("X-Invr-Zip-Skipped",
                        String.valueOf(plan.skippedWriting() + plan.skippedUnavailable()))
                .body(body);
    }
}
