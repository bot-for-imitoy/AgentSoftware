package com.agent.software.adapters.trace;

import com.agent.software.domain.TaskStatus;
import com.agent.software.kernel.RoleId;
import com.agent.software.kernel.TaskId;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.ports.TracePort;
import com.agent.software.kernel.Json;
import com.agent.software.adapters.web.ChatStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link TracePort} backed by the Web chat feed ({@code ChatStore}).
 *
 * <p>Replaces the {@code AgentRole.recordXxx} methods. Trace text is truncated to
 * keep the feed bounded.
 */
public final class ChatTraceAdapter implements TracePort {

    private static final int REASON_MAX = 4000;
    private static final int ANSWER_MAX = 8000;
    private static final int RESULT_MAX = 4000;
    private static final int ARGS_MAX = 2000;

    private final ChatStore store;

    public ChatTraceAdapter(ChatStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    @Override
    public void reason(RoleId role, int round, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        store.record(ChatStore.KIND_REASON, "", role.value(), "", "", "",
                truncate(text.strip(), REASON_MAX), null, extra(round, null));
    }

    @Override
    public void note(RoleId role, int round, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        store.record(ChatStore.KIND_NOTE, "", role.value(), "", "", "",
                truncate(text.strip(), REASON_MAX), null, extra(round, null));
    }

    @Override
    public void tool(RoleId role, int round, ToolCall call, ToolResult result) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tool", call.name());
        extra.put("args", truncate(Json.stringify(call.arguments().asMap()), ARGS_MAX));
        extra.put("result", truncate(result == null ? "" : result.text(), RESULT_MAX));
        extra.put("round", round);
        store.record(ChatStore.KIND_TOOL, "", role.value(), "", "", "", "", null, extra);
    }

    @Override
    public void answer(RoleId role, TaskId task, TaskStatus status, int tokens, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("status", status == null ? "done" : status.wireName());
        extra.put("tokens", tokens);
        if (task != null) {
            extra.put("taskId", task.value());
        }
        store.record(ChatStore.KIND_ANSWER, "", role.value(), "", "", "",
                truncate(text.strip(), ANSWER_MAX), null, extra);
    }

    @Override
    public void notice(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        store.record(ChatStore.KIND_NOTE, "", "", "System", "", "", text, null, Map.of());
    }

    private static Map<String, Object> extra(int round, String taskId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("round", round);
        if (taskId != null) {
            m.put("taskId", taskId);
        }
        return m;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
