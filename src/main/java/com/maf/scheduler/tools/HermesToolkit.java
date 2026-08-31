package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.computers.Computer;
import com.maf.scheduler.utils.Json;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hermes 工具类 (Hermes ToolKit) — Python 版 hermes_toolkit.py.
 *
 * 调用角色电脑 (容器) 上安装的 Hermes Agent:
 *   - hermes_new_conversation: 新建对话 → 返回对话 id (session_id)
 *   - hermes_send: 向指定对话发送内容, 同步等待 Hermes 跑完拿到最终回答
 */
public final class HermesToolkit {

    private static final int HERMES_TIMEOUT = 600;
    private static final String INIT_PROMPT = "你好，开始一个新的对话。";
    private static final Pattern SID_RE = Pattern.compile("hermes --resume ([0-9a-f_]+)");
    private static final Pattern SID_ONLY_RE = Pattern.compile("^[0-9a-f_]+$");

    private HermesToolkit() {
    }

    /** 创建 Hermes 工具类. */
    public static ToolKit createHermesToolkit() {
        ToolKit tk = new ToolKit("hermes", "Hermes 工具类: 调用电脑上的 Hermes Agent");

        ToolHandler newConversation = args -> {
            Computer comp;
            try {
                comp = computer(tk);
            } catch (RuntimeException exc) {
                return "错误: " + exc.getMessage();
            }
            String cmd = "hermes chat -q " + PodmanQuote(INIT_PROMPT) + " 2>&1 | tail -40";
            String out = comp.runCommand(cmd, HERMES_TIMEOUT, 4000);
            if (out.startsWith("[exit") || out.startsWith("错误")) {
                return errorHint(out);
            }
            Matcher m = SID_RE.matcher(out);
            if (m.find()) {
                String sid = m.group(1);
                return "对话已创建, 对话 id: " + sid + " (用 hermes_send 发送内容)";
            }
            return errorHint(out.isEmpty() ? "(无输出)" : out);
        };

        ToolHandler hermesSend = args -> {
            String cid = Json.str(args, "conversation_id", "").strip();
            String content = Json.str(args, "content", "");
            if (cid.isEmpty()) {
                return "错误: 'conversation_id' (对话 id) 为必填参数.";
            }
            if (content.isEmpty()) {
                return "错误: 'content' (发送内容) 为必填参数.";
            }
            if (!SID_ONLY_RE.matcher(cid).matches()) {
                return "错误: 对话 id 格式非法: '" + cid + "' (应为 hermes_new_conversation 返回的 id)";
            }
            Computer comp;
            try {
                comp = computer(tk);
            } catch (RuntimeException exc) {
                return "错误: " + exc.getMessage();
            }
            String cmd = "hermes chat -q " + PodmanQuote(content) + " -r " + PodmanQuote(cid) + " -Q 2>&1";
            String out = comp.runCommand(cmd, HERMES_TIMEOUT, 100_000);
            if (out.startsWith("[exit") || out.startsWith("错误")) {
                return errorHint(out);
            }
            if (out.strip().isEmpty()) {
                return "(Hermes 未返回内容)";
            }
            List<String> cleaned = new ArrayList<>();
            for (String line : out.split("\n")) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("↻") || l.startsWith("session_id:")) {
                    continue;
                }
                cleaned.add(l);
            }
            return cleaned.isEmpty() ? out.strip() : String.join("\n", cleaned);
        };

        Map<String, Object> sendSchema = new LinkedHashMap<>();
        sendSchema.put("type", "object");
        sendSchema.put("properties", Map.of(
                "conversation_id", TalkToolkit.mapOf("string", "对话 id (hermes_new_conversation 返回)"),
                "content", TalkToolkit.mapOf("string", "要发送的内容/任务描述")));
        sendSchema.put("required", List.of("conversation_id", "content"));

        tk.addPythonTool("hermes_new_conversation",
                "在电脑上的 Hermes Agent 中新建一个对话, 返回对话 id. 之后用 hermes_send 向该对话发送内容并拿回复.",
                TalkToolkit.emptySchema(), newConversation);
        tk.addPythonTool("hermes_send",
                "向指定 Hermes 对话 (conversation_id) 发送一段内容, "
                        + "然后同步等待 Hermes 完成全部处理 (含工具调用) 并返回最终结果. "
                        + "适合委托电脑上的 Hermes 独立完成一个子任务.",
                sendSchema, hermesSend);
        return tk;
    }

    private static Computer computer(ToolKit tk) {
        AgentRole role = (AgentRole) tk.get("role", null);
        if (role == null) {
            throw new RuntimeException("Hermes 工具类尚未绑定角色, 请通过 role.add_toolkit() 注册");
        }
        return role.computer();
    }

    private static String PodmanQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 把 hermes 错误转成给角色的可读提示. */
    private static String errorHint(String raw) {
        String text = raw.length() > 300 ? raw.substring(0, 300) : raw;
        if (text.contains("Configure Hermes") || text.toLowerCase().contains("wizard")
                || text.contains("model.provider")) {
            return "错误: 电脑上的 Hermes 尚未配置模型, 无法对话: ("
                    + (text.length() > 100 ? text.substring(0, 100) : text).strip()
                    + ") 需要先在电脑上配置模型/API key";
        }
        if (text.toLowerCase().contains("not found") || text.contains("No such file")) {
            return "错误: 电脑上未安装 Hermes Agent: " + (text.length() > 120 ? text.substring(0, 120) : text);
        }
        if (text.strip().isEmpty()) {
            return "错误: Hermes 调用失败 (无输出)";
        }
        return "错误: Hermes 调用失败: " + text;
    }

    /** 将角色绑定到 Hermes 工具类. */
    public static void bindHermesToToolkit(ToolKit toolkit, AgentRole role) {
        toolkit.bind("role", role);
    }
}
