package com.agent.software.tools.toolkits.hermes;


import com.agent.software.computers.Computer;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * hermes_send - send a piece of content to the specified Hermes conversation (conversation_id), then
 * synchronously wait for Hermes to finish all processing (including tool calls) and return the final result.
 * Suitable for delegating an independent subtask to the Hermes on the computer.
 */
public class HermesSend extends Tool {

    private static final int HERMES_TIMEOUT = 600;
    private static final Pattern SID_ONLY_RE = Pattern.compile("^[0-9a-f_]+$");

    private final Computer computer;

    public HermesSend(Computer computer) {
        super();
        this.computer = computer;
    }

    @Override
    public String getToolName() {
        return "hermes_send";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("conversation_id", "The conversation id (returned by hermes_new_conversation).");
        schema.put("content", "The content / task description to send.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object ocid = args.get("conversation_id");
        Object ocontent = args.get("content");
        if (!(ocid instanceof String)) {
            return ocid == null
                    ? "hermes_send: Error: needs conversation_id"
                    : "hermes_send: Error: conversation_id is not a string";
        }
        if (!(ocontent instanceof String)) {
            return ocontent == null
                    ? "hermes_send: Error: needs content"
                    : "hermes_send: Error: content is not a string";
        }
        String cid = ((String) ocid).strip();
        String content = (String) ocontent;
        if (cid.isEmpty()) {
            return "hermes_send: Error: needs conversation_id";
        }
        if (content.isEmpty()) {
            return "hermes_send: Error: needs content";
        }
        if (!SID_ONLY_RE.matcher(cid).matches()) {
            return "hermes_send: Error: invalid conversation id format: '" + cid
                    + "' (should be the id returned by hermes_new_conversation)";
        }
        String cmd = "hermes chat -q " + PodmanQuote(content) + " -r " + PodmanQuote(cid) + " -Q 2>&1";
        String out = computer.runCommand(cmd, HERMES_TIMEOUT, 100_000);
        if (out.startsWith("[exit") || out.startsWith("Error")) {
            return errorHint(out);
        }
        if (out.strip().isEmpty()) {
            return "hermes_send: (Hermes returned no content)";
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
    }

    /** Converts a hermes error into a readable hint for the role. */
    private static String errorHint(String raw) {
        String text = raw.length() > 300 ? raw.substring(0, 300) : raw;
        if (text.contains("Configure Hermes") || text.toLowerCase().contains("wizard")
                || text.contains("model.provider")) {
            return "hermes_send: Error: Hermes on the computer has no model configured yet, cannot chat: ("
                    + (text.length() > 100 ? text.substring(0, 100) : text).strip()
                    + ") configure the model/API key on the computer first";
        }
        if (text.toLowerCase().contains("not found") || text.contains("No such file")) {
            return "hermes_send: Error: Hermes Agent is not installed on the computer: "
                    + (text.length() > 120 ? text.substring(0, 120) : text);
        }
        if (text.strip().isEmpty()) {
            return "hermes_send: Error: Hermes call failed (no output)";
        }
        return "hermes_send: Error: Hermes call failed: " + text;
    }

    private static String PodmanQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
