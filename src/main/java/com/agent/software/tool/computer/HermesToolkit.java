package com.agent.software.tool.computer;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hermes 工具包（id {@code "hermes"}，默认不装配），暴露工具：hermes_send / hermes_new_conversation（经由构造期注入的 {@link Shell} 调用外部 Hermes 通道）。
 *
 * <p>对齐 master {@code toolkits/hermes}：两个工具就是在电脑上执行 {@code hermes chat ...}
 * 命令并解析输出——{@code hermes_new_conversation} 从输出里抓
 * {@code hermes --resume <id>} 得到会话 id，{@code hermes_send} 用该 id 续聊。
 * 失败统一走 {@link ToolResult#error(String)}，不再用 {@code "Error:"} 前缀表达。
 */
public final class HermesToolkit implements Toolkit {

    /** Hermes 单次调用超时（秒）：LLM 处理 + 工具调用可能很久，给足时间。 */
    private static final Duration HERMES_TIMEOUT = Duration.ofSeconds(600);

    /** 会话 id 允许的字符（对齐 master 的 {@code [0-9a-f_]+}）。 */
    private static final Pattern SID_ONLY = Pattern.compile("^[0-9a-f_]+$");

    /** 新会话输出里的 resume 提示。 */
    private static final Pattern SID = Pattern.compile("hermes --resume ([0-9a-f_]+)");

    /** 建会话用的初始消息（对齐 master 原文）。 */
    private static final String INIT_PROMPT = "Hello, let's start a new conversation.";

    private final Shell shell;

    public HermesToolkit(Shell shell) {
        this.shell = shell;
    }

    @Override
    public String id() {
        return "hermes";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new HermesSend(), new HermesNewConversation());
    }

    /** POSIX 单引号引用（内容里可能含单引号 / 换行）。 */
    private static String quote(String value) {
        return ShellSupport.quote(value);
    }

    /** 把 Hermes 的原始报错翻成角色能直接照做的提示。 */
    private static String errorHint(String raw) {
        String text = raw == null ? "" : raw.strip();
        String head = text.length() > 300 ? text.substring(0, 300) : text;
        String lower = head.toLowerCase(java.util.Locale.ROOT);
        if (head.contains("Configure Hermes") || lower.contains("wizard") || head.contains("model.provider")) {
            return "电脑上的 Hermes 还没配置模型，无法对话：(" + trim(head, 100) + ") 请先在电脑上配置模型/API Key。";
        }
        if (lower.contains("not found") || head.contains("No such file")) {
            return "电脑上没有安装 Hermes Agent：" + trim(head, 120);
        }
        if (head.isEmpty()) {
            return "Hermes 调用失败（无输出）。";
        }
        return "Hermes 调用失败：" + head;
    }

    private static String trim(String value, int max) {
        String v = value == null ? "" : value.strip();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** 清理 Hermes 输出中的噪声行（进度条、session_id 提示）。 */
    private static String clean(String raw) {
        List<String> kept = new ArrayList<>();
        for (String line : (raw == null ? "" : raw).split("\n")) {
            String value = line.strip();
            if (value.isEmpty() || value.startsWith("↻") || value.startsWith("session_id:")) {
                continue;
            }
            kept.add(value);
        }
        return String.join("\n", kept);
    }

    private ToolResult noShell() {
        return ToolResult.error("未注入电脑（Shell），无法调用 Hermes。");
    }

    /** hermes_send：向指定 Hermes 会话发送内容并同步等待最终结果。 */
    private final class HermesSend implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("hermes_send",
                    "向电脑上 Hermes Agent 的指定会话发送内容，同步等待处理完成后返回最终结果，适合把独立子任务外包给它。",
                    JsonSchema.object()
                            .string("conversation_id", "会话 id（由 hermes_new_conversation 返回）。")
                            .string("content", "要发送的内容 / 任务描述。")
                            .required("conversation_id", "content"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (shell == null) {
                return noShell();
            }
            String cid = arguments == null ? "" : arguments.stringOr("conversation_id", "").strip();
            String content = arguments == null ? "" : arguments.stringOr("content", "");
            if (cid.isEmpty()) {
                return ToolResult.error("hermes_send 需要 conversation_id 参数。");
            }
            if (content.isEmpty()) {
                return ToolResult.error("hermes_send 需要 content 参数。");
            }
            if (!SID_ONLY.matcher(cid).matches()) {
                return ToolResult.error("hermes_send: 非法的 conversation_id: '" + cid
                        + "'（应使用 hermes_new_conversation 返回的 id）。");
            }
            String command = "hermes chat -q " + quote(content) + " -r " + quote(cid) + " -Q 2>&1";
            Shell.CommandResult result = shell.run(command, HERMES_TIMEOUT, 100_000);
            if (!result.ok()) {
                return ToolResult.error("hermes_send: " + errorHint(result.combined()));
            }
            String cleaned = clean(result.combined());
            return ToolResult.ok(cleaned.isEmpty() ? "hermes_send: (Hermes 未返回内容)" : cleaned);
        }
    }

    /** hermes_new_conversation：开一个新会话并返回 conversation_id。 */
    private final class HermesNewConversation implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("hermes_new_conversation",
                    "在电脑上的 Hermes Agent 中新建一个会话，返回 conversation_id；随后用 hermes_send 向该会话发送内容。",
                    JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (shell == null) {
                return noShell();
            }
            String command = "hermes chat -q " + quote(INIT_PROMPT) + " 2>&1 | tail -40";
            Shell.CommandResult result = shell.run(command, HERMES_TIMEOUT, 4_000);
            if (!result.ok()) {
                return ToolResult.error("hermes_new_conversation: " + errorHint(result.combined()));
            }
            Matcher matcher = SID.matcher(result.combined());
            if (matcher.find()) {
                return ToolResult.ok("hermes_new_conversation: 会话已创建，conversation_id = " + matcher.group(1)
                        + "（用 hermes_send 发送内容）。");
            }
            return ToolResult.error("hermes_new_conversation: " + errorHint(result.combined()));
        }
    }
}
