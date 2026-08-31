package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * todo_list — 列出自己的 Todo 待办清单 (可按状态过滤: pending/in_progress/completed).
 */
public class TodoList extends Tool {

    private final TodoStore todoStore;

    public TodoList(TodoStore todoStore) {
        super();
        this.todoStore = todoStore;
    }

    @Override
    public String getToolName() {
        return "todo_list";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("status", "(Optional) Filter by status: pending / in_progress / completed.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object ostatus = args.get("status");
        String status = ostatus instanceof String s && !s.strip().isEmpty() ? s.strip() : null;
        List<Map<String, Object>> items = this.todoStore.list(status);
        if (items.isEmpty()) {
            String hint = status != null ? " (状态=" + status + ")" : "";
            return "todo_list: (暂无待办" + hint + ")";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> i : items) {
            lines.add("- " + fmt(i));
        }
        String head = "待办清单 (" + items.size() + " 项";
        if (status != null) {
            head += ", 状态=" + status;
        }
        return head + "):\n" + String.join("\n", lines);
    }

    private static String fmt(Map<String, Object> item) {
        String mark = switch (String.valueOf(item.getOrDefault("status", "pending"))) {
            case "in_progress" -> "🔄";
            case "completed" -> "✅";
            default -> "⬜";
        };
        String base = "[" + item.get("id") + "] " + mark + " " + item.getOrDefault("status", "pending")
                + ": " + item.get("title");
        Object detail = item.get("detail");
        if (detail != null && !String.valueOf(detail).isEmpty()) {
            base += " — " + detail;
        }
        return base;
    }
}
