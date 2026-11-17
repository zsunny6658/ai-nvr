package com.invr.web.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v1.2.22 目录选择器 /api/settings/browse：
 * 不存在的路径默认 400 并提示；create=1 时逐级创建后正常返回（手动路径兜底）。
 * browse 只依赖文件系统与环境探测，控制器依赖全部传 null 即可。
 */
class SettingsBrowseTest {

    @TempDir
    Path tmp;

    private MockMvc mvc() {
        SettingsController controller = new SettingsController(null, null, null, null, null, null, null, null, null, null, null, null, null);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void missingPathWithoutCreateIs400() throws Exception {
        String target = tmp.resolve("a/b/c").toString();
        mvc().perform(get("/api/settings/browse").param("path", target))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("目录不存在")));
        assertTrue(!Files.exists(tmp.resolve("a")), "未带 create=1 不应创建目录");
    }

    @Test
    void createCreatesMissingLevelsAndReturns() throws Exception {
        String target = tmp.resolve("a/b/c").toString();
        mvc().perform(get("/api/settings/browse").param("path", target).param("create", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentWritable").value(true))
                .andExpect(jsonPath("$.entries").isArray());
        assertTrue(Files.isDirectory(tmp.resolve("a/b/c")), "create=1 应逐级创建目录");
    }

    @Test
    void createFailsWhenParentReadOnly() throws Exception {
        // 父级只读：创建失败应返回 400 而不是 500
        Path ro = tmp.resolve("ro");
        Files.createDirectories(ro);
        boolean setReadOnly = ro.toFile().setWritable(false);
        try {
            if (!setReadOnly && System.getProperty("os.name").toLowerCase().contains("mac")) {
                // macOS 个人目录下 setWritable 偶尔无效，此时跳过该用例
                return;
            }
            mvc().perform(get("/api/settings/browse")
                            .param("path", ro.resolve("x").toString()).param("create", "1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(
                            org.hamcrest.Matchers.containsString("自动创建失败")));
        } finally {
            ro.toFile().setWritable(true);
        }
    }

    @Test
    void existingDirStillWorks() throws Exception {
        Path dir = tmp.resolve("exists");
        Files.createDirectories(dir);
        mvc().perform(get("/api/settings/browse").param("path", dir.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentWritable").value(true));
    }

    @Test
    void missingLeadingSlashIsNormalizedToAbsolute() throws Exception {
        // v1.2.23：手动输入漏写前导 / 时按绝对路径处理，不得按进程 CWD 拼接。
        // 构造已存在的绝对目录，去掉首位 / 后作为参数传入，应返回同一目录。
        Path dir = tmp.resolve("noslash");
        Files.createDirectories(dir);
        String abs = dir.toFile().getCanonicalPath();
        String withoutSlash = abs.startsWith("/") ? abs.substring(1) : abs;
        mvc().perform(get("/api/settings/browse")
                        .param("path", withoutSlash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(abs));
    }

    @Test
    void trailingSlashesAreTrimmed() throws Exception {
        Path dir = tmp.resolve("trail");
        Files.createDirectories(dir);
        mvc().perform(get("/api/settings/browse")
                        .param("path", dir.toAbsolutePath() + "//"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(dir.toFile().getCanonicalPath()));
    }
}
