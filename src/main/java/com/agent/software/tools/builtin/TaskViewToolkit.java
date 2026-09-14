package com.agent.software.tools.builtin;

import com.agent.software.domain.Task;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.ports.TeamPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.List;

/** The {@code task_view} toolkit: a role's own pending queue and recent history. */
public final class TaskViewToolkit {

    private static final int HISTORY_LIMIT = 10;

    private TaskViewToolkit() {
    }

    public static Toolkit create(TeamPort team) {
        Tool myTasks = Tools.of("my_tasks", "View my pending tasks and recent task history",
                JsonSchema.builder()
                        .property("scope", JsonSchema.Property.stringEnum(
                                "(Optional) Which tasks to show.", "all", "pending", "done", "failed"))
                        .build(),
                (role, call) -> {
                    String scope = Tools.argStripped(call, "scope").toLowerCase(java.util.Locale.ROOT);
                    if (scope.isEmpty()) {
                        scope = "all";
                    }
                    List<Task> pending = team.pendingTasks(role);
                    List<Task> history = team.taskHistory(role, HISTORY_LIMIT);

                    StringBuilder sb = new StringBuilder();
                    if (scope.equals("all") || scope.equals("pending")) {
                        sb.append("\ud83d\udce5 Pending (queue ").append(pending.size()).append(")");
                        if (pending.isEmpty()) {
                            sb.append(" \u2014 empty");
                        } else {
                            for (Task t : pending) {
                                sb.append("\n- [id=").append(t.id()).append("] urgency=")
                                        .append(t.urgency()).append(" | ").append(truncate(t.description(), 120));
                            }
                        }
                    }
                    if (scope.equals("all") || scope.equals("done") || scope.equals("failed")) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append("\ud83d\udccb Recent tasks (").append(history.size()).append(")");
                        for (int i = history.size() - 1; i >= 0; i--) {
                            Task t = history.get(i);
                            String status = t.status().wireName();
                            if (!scope.equals("all") && !scope.equals(status)) {
                                continue;
                            }
                            sb.append("\n- ").append(status.equals("done") ? "\u2705" : "\u274c")
                                    .append(" [").append(status).append(", ").append(t.tokensConsumed())
                                    .append(" tokens] ").append(truncate(t.description(), 100));
                        }
                    }
                    return ToolResult.success(sb.toString().strip());
                });

        return new Toolkit("task_view", "Task list toolkit: view my pending queue and recent task history",
                List.of(myTasks));
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
