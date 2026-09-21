package com.agent.software.tools.toolkits.taskview;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * my_tasks — view the list of tasks assigned to me: pending queue (dispatched by the system/colleagues, not yet started)
 * + recent completed/failed task history (with results and token consumption).
 * scope options: all (default) / pending (queue only) / done / failed.
 */
public class MyTasks extends Tool {

    private static final int HISTORY_LIMIT = 10;

    private final Role role;

    public MyTasks(Role role) {
        super();
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "my_tasks";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("scope", "(Optional) all / pending / done / failed.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oscope = args.get("scope");
        String scope = oscope instanceof String s && !s.strip().isEmpty()
                ? s.strip().toLowerCase() : "all";
        List<Role.Task> queue = role.pendingTasks();
        List<String> pendingLines = new ArrayList<>();
        for (Role.Task t : queue) {
            String desc = t.description.length() > 120 ? t.description.substring(0, 120) : t.description;
            pendingLines.add("- [id=" + t.taskId + "] urgency=" + t.urgency + " | " + desc);
        }
        List<Role.Task> history = role.taskHistory(HISTORY_LIMIT);
        List<String> histLines = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            Role.Task t = history.get(i);
            String mark = "done".equals(t.status) ? "✅" : "❌";
            String desc = t.description.length() > 100 ? t.description.substring(0, 100) : t.description;
            histLines.add("- " + mark + " [" + t.status + ", " + t.tokensConsumed + " tokens] " + desc);
        }
        List<String> parts = new ArrayList<>();
        if (scope.equals("all") || scope.equals("pending")) {
            String head = "📥 Pending (queue " + pendingLines.size() + ")";
            parts.add(head + (pendingLines.isEmpty() ? " — empty" : "\n" + String.join("\n", pendingLines)));
        }
        if (scope.equals("all") || scope.equals("done") || scope.equals("failed")) {
            if (scope.equals("done") || scope.equals("failed")) {
                histLines.removeIf(l -> !l.contains("[" + scope + ","));
            }
            String head = "📋 Recent tasks (" + histLines.size() + ")";
            parts.add(head + (histLines.isEmpty() ? " — empty" : "\n" + String.join("\n", histLines)));
        }
        return String.join("\n\n", parts);
    }
}
