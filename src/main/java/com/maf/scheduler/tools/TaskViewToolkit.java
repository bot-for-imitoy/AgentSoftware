package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.Json;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 任务列表工具类 (Task View ToolKit) — Python 版 task_view_toolkit.py.
 *
 * 包含: my_tasks (待处理队列 + 最近历史).
 */
public final class TaskViewToolkit {

    private static final int HISTORY_LIMIT = 10;

    private TaskViewToolkit() {
    }

    /** 创建任务列表工具类. */
    public static ToolKit createTaskViewToolkit() {
        ToolKit tk = new ToolKit("task_view", "任务列表工具类: 查看分配给我的任务 (队列+历史)");

        ToolHandler myTasks = args -> {
            AgentRole role = (AgentRole) tk.get("role", null);
            if (role == null) {
                throw new RuntimeException("任务列表工具类尚未绑定角色, 请通过 role.add_toolkit() 注册");
            }
            String scope = Json.str(args, "scope", "all").strip().toLowerCase();
            List<AgentRole.Task> queue = role.pendingTasks();
            List<String> pendingLines = new ArrayList<>();
            for (AgentRole.Task t : queue) {
                String desc = t.description.length() > 120 ? t.description.substring(0, 120) : t.description;
                pendingLines.add("- [id=" + t.taskId + "] 紧急度=" + t.urgency + " | " + desc);
            }
            List<AgentRole.Task> history = role.taskHistory(HISTORY_LIMIT);
            List<String> histLines = new ArrayList<>();
            for (int i = history.size() - 1; i >= 0; i--) {
                AgentRole.Task t = history.get(i);
                String mark = "done".equals(t.status) ? "✅" : "❌";
                String desc = t.description.length() > 100 ? t.description.substring(0, 100) : t.description;
                histLines.add("- " + mark + " [" + t.status + ", " + t.tokensConsumed + " tokens] " + desc);
            }
            List<String> parts = new ArrayList<>();
            if (scope.equals("all") || scope.equals("pending")) {
                String head = "📥 待处理 (队列 " + pendingLines.size() + " 个)";
                parts.add(head + (pendingLines.isEmpty() ? " — 空" : "\n" + String.join("\n", pendingLines)));
            }
            if (scope.equals("all") || scope.equals("done") || scope.equals("failed")) {
                if (scope.equals("done") || scope.equals("failed")) {
                    histLines.removeIf(l -> !l.contains("[" + scope + ","));
                }
                String head = "📋 最近任务 (" + histLines.size() + " 条)";
                parts.add(head + (histLines.isEmpty() ? " — 空" : "\n" + String.join("\n", histLines)));
            }
            return String.join("\n\n", parts);
        };

        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "scope", TalkToolkit.mapOf("string", "范围: all/pending/done/failed (可选)")));

        tk.addPythonTool("my_tasks",
                "查看分配给我的任务列表: 待处理队列 (系统/同事派发、还没开始) "
                        + "+ 最近完成/失败的任务历史 (含结果与 token 消耗). "
                        + "想了解自己当前有什么活没干、干过什么时用这个. "
                        + "scope 可选: all(默认)/pending(只看队列)/done/failed.",
                schema, myTasks);
        return tk;
    }

    /** 将角色绑定到任务列表工具类. */
    public static void bindRoleToToolkit(ToolKit toolkit, AgentRole role) {
        toolkit.bind("role", role);
    }
}
