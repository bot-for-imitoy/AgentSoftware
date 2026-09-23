package com.agent.software.tools.toolkits.task;

import com.agent.software.event.Event;
import com.agent.software.event.Task;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * my_tasks：我队列里还没处理的（事件/任务）+ 最近完成/失败的任务历史。
 *
 * <p>scope：all（默认）/ pending / done / failed。
 */
public class MyTasks extends Tool {

    private static final int HISTORY_LIMIT = 10;

    private final Role role;

    public MyTasks(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "my_tasks";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("scope", "(Optional) all (default) / pending (queue only) / done / failed.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Show the events/tasks still queued for me, plus my most recent finished tasks "
                + "(with status and token cost). Scheduled-but-not-due tasks are in list_tasks.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null) {
            return "my_tasks error: no role";
        }
        String scope = TaskSupport.str(args.get("scope")).trim().toLowerCase();
        if (scope.isEmpty()) {
            scope = "all";
        }
        if (!scope.equals("all") && !scope.equals("pending") && !scope.equals("done") && !scope.equals("failed")) {
            return "my_tasks error: scope must be all / pending / done / failed, got '" + scope + "'";
        }
        List<String> parts = new ArrayList<>();
        if (scope.equals("all") || scope.equals("pending")) {
            parts.add(pendingSection());
        }
        if (scope.equals("all") || scope.equals("done") || scope.equals("failed")) {
            parts.add(historySection(scope));
        }
        return String.join("\n\n", parts);
    }

    private String pendingSection() {
        List<Event> pending = role.pendingEvents();
        StringBuilder sb = new StringBuilder("Pending (queue " + pending.size() + ")");
        if (pending.isEmpty()) {
            return sb.append(" — empty").toString();
        }
        sb.append('\n');
        for (Event e : pending) {
            sb.append("- ").append(e.type).append(" / ").append(e.priority)
                    .append(e instanceof Task t ? (" / status " + t.status) : "")
                    .append(" / from ").append(e.fromRoleId == null || e.fromRoleId.isBlank() ? "system" : e.fromRoleId)
                    .append(" / ").append(Text.truncate(e.content == null ? "" : e.content.replace('\n', ' '), 160))
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private String historySection(String scope) {
        List<Task> history = role.taskHistory(HISTORY_LIMIT);
        List<String> lines = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            Task t = history.get(i);
            if (scope.equals("done") && !Task.DONE.equals(t.status)) {
                continue;
            }
            if (scope.equals("failed") && !Task.FAILED.equals(t.status)) {
                continue;
            }
            lines.add("- " + (Task.DONE.equals(t.status) ? "done" : t.status)
                    + " [" + TaskSupport.shortId(t.uuid) + ", " + t.tokensConsumed + " tokens] "
                    + Text.truncate(t.content == null ? "" : t.content.replace('\n', ' '), 120)
                    + " => " + Text.truncate(t.result == null ? "" : t.result.replace('\n', ' '), 120));
        }
        String head = "Recent tasks (" + lines.size() + ")";
        return lines.isEmpty() ? head + " — empty" : head + "\n" + String.join("\n", lines);
    }
}
