package com.agent.software.tools.toolkits.hermes;


import com.agent.software.computers.Computer;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * hermes_new_conversation — 在电脑上的 Hermes Agent 中新建一个对话,
 * 返回对话 id. 之后用 hermes_send 向该对话发送内容并拿回复.
 */
public class HermesNewConversation extends Tool {

    private static final int HERMES_TIMEOUT = 600;
    private static final String INIT_PROMPT = "你好，开始一个新的对话。";
    private static final Pattern SID_RE = Pattern.compile("hermes --resume ([0-9a-f_]+)");

    private final Computer computer;

    public HermesNewConversation(Computer computer) {
        super();
        this.computer = computer;
    }

    @Override
    public String getToolName() {
        return "hermes_new_conversation";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        String cmd = "hermes chat -q " + PodmanQuote(INIT_PROMPT) + " 2>&1 | tail -40";
        String out = computer.runCommand(cmd, HERMES_TIMEOUT, 4000);
        if (out.startsWith("[exit") || out.startsWith("错误")) {
            return errorHint(out);
        }
        Matcher m = SID_RE.matcher(out);
        if (m.find()) {
            String sid = m.group(1);
            return "hermes_new_conversation: 对话已创建, 对话 id: " + sid + " (用 hermes_send 发送内容)";
        }
        return errorHint(out.isEmpty() ? "(无输出)" : out);
    }

    /** 把 hermes 错误转成给角色的可读提示. */
    private static String errorHint(String raw) {
        String text = raw.length() > 300 ? raw.substring(0, 300) : raw;
        if (text.contains("Configure Hermes") || text.toLowerCase().contains("wizard")
                || text.contains("model.provider")) {
            return "hermes_new_conversation: Error: 电脑上的 Hermes 尚未配置模型, 无法对话: ("
                    + (text.length() > 100 ? text.substring(0, 100) : text).strip()
                    + ") 需要先在电脑上配置模型/API key";
        }
        if (text.toLowerCase().contains("not found") || text.contains("No such file")) {
            return "hermes_new_conversation: Error: 电脑上未安装 Hermes Agent: "
                    + (text.length() > 120 ? text.substring(0, 120) : text);
        }
        if (text.strip().isEmpty()) {
            return "hermes_new_conversation: Error: Hermes 调用失败 (无输出)";
        }
        return "hermes_new_conversation: Error: Hermes 调用失败: " + text;
    }

    private static String PodmanQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
