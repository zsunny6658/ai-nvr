package com.invr.web.api;

import com.invr.web.api.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行信息端点：返回当前构建版本、JVM 启动时间与已运行时长。
 *
 * <p>版本号以 fnOS 解包后落在 {@code app.jar} 同级的 {@code manifest} 文件为准（由打包脚本写入，
 * 如 {@code version=1.2.8}）。fnOS 不会把该文件打进 jar，因此运行时直接读取同级文件最准确；
 * 非 fnOS 环境（本地 {@code java -jar} 调试）读不到时回落到内置常量。</p>
 *
 * <p>该端点不含任何敏感信息，但仍在 {@code /api/**} 门禁内，避免被外部未授权探测到部署形态。</p>
 */
@RestController
public class AboutController {

    /** 非 fnOS 环境（本地调试）读不到 manifest 时的回落版本。需随每次发版同步手工维护。 */
    private static final String FALLBACK_VERSION = "1.2.9";

    @GetMapping("/api/about")
    public Map<String, Object> about() {
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        long now = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", resolveVersion());
        m.put("startTime", start);
        m.put("uptimeMs", Math.max(0, now - start));
        return m;
    }

    private String resolveVersion() {
        try {
            URI loc = AboutController.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File jar = new File(loc);
            File manifest = new File(jar.getParentFile(), "manifest");
            if (manifest.isFile()) {
                for (String line : Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("version=")) {
                        return t.substring("version=".length()).trim();
                    }
                }
            }
        } catch (Exception ignored) {
            // 读不到时回落内置常量，不影响主流程
        }
        return FALLBACK_VERSION;
    }
}
