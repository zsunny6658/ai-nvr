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
    private static final String FALLBACK_VERSION = "1.2.12";

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

    /**
     * 免鉴权版本号端点：锁定页（输入访问密码页）的页脚需要展示版本号，但此时尚未解锁、
     * {@code /api/about} 受门禁保护会返回 401，导致页脚永远停在 “v?”。该端点只暴露版本号、
     * 不含运行时长等敏感信息，列入 {@link WebSecurityConfig} 白名单，任何访客（含锁定态）均可读取，
     * 让页脚始终显示真实版本。
     */
    @GetMapping("/api/version")
    public Map<String, Object> version() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", resolveVersion());
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
                    // fnpack 打包时会把 manifest 规范化成 "version               = 1.2.12"
                    // （等号两侧填充空格），fnOS 安装后又会写成紧凑的 "version=1.2.12"；
                    // 两种都兼容：按首个 '=' 拆分、分别 trim key 与 value。
                    int eq = t.indexOf('=');
                    if (eq > 0 && "version".equalsIgnoreCase(t.substring(0, eq).trim())) {
                        String v = t.substring(eq + 1).trim();
                        if (!v.isEmpty()) {
                            return v;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 读不到时回落内置常量，不影响主流程
        }
        return FALLBACK_VERSION;
    }
}
