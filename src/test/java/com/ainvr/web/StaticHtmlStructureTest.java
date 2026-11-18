package com.ainvr.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置页 HTML 结构契约回归。
 *
 * <p>背景：v2.2.3 设置页分组化重构时，python 脚本搬移卡片留下一个未闭合的
 * {@code </div>}（RTSP 取流卡收尾缺失），导致 aiCard / backupCard / tab-boards
 * 三张卡层层嵌套进前一张卡。设置分组切换按 {@code hidden} 隐藏非当前组卡片时，
 * 嵌在隐藏外层里的卡片跟着不可见——用户侧表现为「设置中 AI 检测和共享看板
 * tab 页内容为空」，且连坏三个版本（2.2.3~2.2.5）未被发现。</p>
 *
 * <p>两层检查（纯标记扫描，index.html 无内联脚本）：
 * <ol>
 *   <li>div 开闭配平：文件末尾栈空、无多余关闭；</li>
 *   <li>设置分组卡片必须是 settings-body 容器的直接子元素——
 *       嵌套进其他卡片即判失败，直接拦住本类事故。</li>
 * </ol></p>
 */
class StaticHtmlStructureTest {

    private static final Path INDEX = Path.of("src/main/resources/static/index.html");

    private static final Pattern DIV_OPEN = Pattern.compile("<div(?=[\\s>])");
    private static final Pattern DIV_CLOSE = Pattern.compile("</div\\s*>");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** 打开的 div：行号 + 是否带 set-card 类 + 是否带 data-sgroup。 */
    private record OpenDiv(int line, boolean isSetCard, boolean hasSgroup) { }

    @Test
    void divsAreBalancedAndSettingCardsAreDirectChildrenOfSettingsBody() throws Exception {
        String html = COMMENT.matcher(Files.readString(INDEX)).replaceAll("");
        Matcher mo = DIV_OPEN.matcher(html);
        Matcher mc = DIV_CLOSE.matcher(html);

        Deque<OpenDiv> stack = new ArrayDeque<>();
        int bodyDepth = -1;          // settings-body 容器在栈中的深度（0 基）
        int bodyCloseLine = -1;      // settings-body 关闭后不再做子元素校验
        StringBuilder problems = new StringBuilder();

        // 用合并游标按文档顺序处理两类 token
        int openStart = mo.find() ? mo.start() : Integer.MAX_VALUE;
        int closeStart = mc.find() ? mc.start() : Integer.MAX_VALUE;
        while (openStart != Integer.MAX_VALUE || closeStart != Integer.MAX_VALUE) {
            if (openStart < closeStart) {
                int line = 1 + countNewlines(html, openStart);
                int depth = stack.size();
                String tag = tagText(html, openStart);
                boolean isSetCard = tag.contains("set-card");
                boolean hasSgroup = tag.contains("data-sgroup");
                stack.push(new OpenDiv(line, isSetCard, hasSgroup));
                if (tag.contains("settings-body")) {
                    assertTrue(bodyDepth < 0, "settings-body 容器出现多次（行 " + line + "）");
                    bodyDepth = depth;
                }
                // 分组卡片必须是 settings-body 的直接子元素
                if (bodyDepth >= 0 && bodyCloseLine < 0 && isSetCard && hasSgroup
                        && depth != bodyDepth + 1) {
                    problems.append("行 ").append(line)
                            .append("：set-card[data-sgroup] 嵌套在 settings-body 的其他元素里")
                            .append("（深度 ").append(depth).append("，应为 ").append(bodyDepth + 1).append("）\n");
                }
                openStart = mo.find() ? mo.start() : Integer.MAX_VALUE;
            } else {
                int line = 1 + countNewlines(html, closeStart);
                if (stack.isEmpty()) {
                    problems.append("行 ").append(line).append("：多余的 </div>\n");
                } else {
                    OpenDiv closed = stack.pop();
                    if (stack.size() == bodyDepth && bodyDepth >= 0) {
                        bodyCloseLine = line;
                    }
                    assertNotEquals(-1, closed.line);
                }
                closeStart = mc.find() ? mc.start() : Integer.MAX_VALUE;
            }
        }
        assertTrue(stack.isEmpty(), "未闭合的 <div> 共 " + stack.size() + " 个，首个在行 "
                + (stack.isEmpty() ? -1 : stack.peek().line()));
        assertTrue(problems.isEmpty(), "设置卡片嵌套异常：\n" + problems);
        assertNotEquals(-1, bodyDepth, "未找到 settings-body 容器（设置分组结构变更时同步更新本测试）");
        assertNotEquals(-1, bodyCloseLine, "settings-body 容器未闭合");
    }

    /** 取从 start 开始到 '>' 结束的完整开标签文本（属性值不含 '>'，本项目 HTML 满足）。 */
    private static String tagText(String html, int start) {
        int end = html.indexOf('>', start);
        assertEquals(true, end > start, "开标签未找到 > （行 " + (1 + countNewlines(html, start)) + "）");
        return html.substring(start, end);
    }

    private static int countNewlines(String s, int to) {
        int n = 0;
        for (int i = 0; i < to; i++) {
            if (s.charAt(i) == '\n') { n++; }
        }
        return n;
    }
}
