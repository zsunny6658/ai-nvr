package com.ainvr.web;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前端 JS 静态契约回归。
 *
 * <p>背景：v2.1.1 起连续两次真实事故都是「JS 引用了不可见标识符」——
 * {@code renderAiModel is not defined}（声明被嵌进内层作用域，设置页整体挂掉，
 * 用户侧表现为「readeraimodel 不可用之类的」报错）与 {@code pb is not defined}
 * （快捷键处理函数漏 var 声明，回放页按键即抛错）。MockMvc 测试不执行前端 JS，
 * 此类问题只能靠静态分析拦住。</p>
 *
 * <p>两层检查（node 缺失时整体跳过；tsc 缺失时仅保留语法层）：
 * <ol>
 *   <li>{@code node --check}：语法可解析；</li>
 *   <li>{@code tsc --checkJs} 过滤 {@code TS2304 Cannot find name}：
 *       解析级作用域分析，白名单为 script 标签引入的全局（Hls / Watchdog）。</li>
 * </ol></p>
 */
class StaticJsContractTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final List<String> JS_FILES = List.of(
            "app.js", "share.js", "watchdog.js");
    /** script 标签引入的全局（vendor/hls.min.js、watchdog.js），TS2304 白名单。 */
    private static final List<String> SCRIPT_GLOBALS = List.of("Hls", "Watchdog");

    private static boolean hasCommand(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), cmd[0] + " 超时");
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String run(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(Path.of(".").toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "命令超时: " + String.join(" ", cmd));
        return out;
    }

    @Test
    void jsAssetsParseAndHaveNoUnresolvedNames() throws Exception {
        Assumptions.assumeTrue(hasCommand("node", "--version"),
                "node 不可用，跳过前端静态契约检查（本地开发机应安装 node）");

        // 1) 语法层：node --check
        for (String f : JS_FILES) {
            String out = run("node", "--check", STATIC.resolve(f).toString());
            assertTrue(out.isBlank(), f + " 语法错误:\n" + out);
        }

        // 2) 解析层：tsc --checkJs 的 TS2304（Cannot find name）
        List<String> tsc = tscCommand();
        Assumptions.assumeTrue(tsc != null,
                "tsc 不可用（npm i -g typescript 后可启用解析级检查），仅执行语法层");
        String out = run(tsc.toArray(new String[0]));
        List<String> unresolved = new ArrayList<>();
        for (String line : out.split("\\R")) {
            if (!line.contains("error TS2304")) {
                continue;
            }
            String name = extractName(line);
            if (name != null && SCRIPT_GLOBALS.contains(name)) {
                continue;
            }
            unresolved.add(line.trim());
        }
        assertEquals(List.of(), unresolved,
                "前端 JS 存在解析不到的标识符（作用域断裂/未定义），会导致运行时 ReferenceError：\n"
                        + String.join("\n", unresolved));
    }

    /** tsc 命令：优先 PATH，其次常见全局安装位置；都不可用返回 null。 */
    private static List<String> tscCommand() {
        if (hasCommand("tsc", "--version")) {
            return List.of("tsc", "--allowJs", "--checkJs", "--noEmit",
                    "--target", "es2020", "--lib", "es2020,dom");
        }
        for (Path p : List.of(
                Path.of(System.getProperty("user.home"), ".workbuddy/binaries/node/versions/22.22.2-6/bin/tsc"),
                Path.of("/usr/local/bin/tsc"),
                Path.of("/opt/homebrew/bin/tsc"))) {
            if (Files.isRegularFile(p)) {
                return List.of(p.toString(), "--allowJs", "--checkJs", "--noEmit",
                        "--target", "es2020", "--lib", "es2020,dom");
            }
        }
        return null;
    }

    /** 从 "file(line,col): error TS2304: Cannot find name 'X'." 提取 X。 */
    private static String extractName(String line) {
        int i = line.indexOf("Cannot find name '");
        if (i < 0) {
            return null;
        }
        int s = i + "Cannot find name '".length();
        int e = line.indexOf('\'', s);
        return e > s ? line.substring(s, e) : null;
    }
}
