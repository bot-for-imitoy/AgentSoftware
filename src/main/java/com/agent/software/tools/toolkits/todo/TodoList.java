package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** todo_list：列出自己的待办清单（平铺，可按状态过滤）。 */
public class TodoList extends Tool {

    private final TodoStore store;

    public TodoList(TodoStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "todo_list";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("status", "(Optional) pending / in_progress / completed / all (default all).");
        return schema;
    }

    @Override
    public String getDescription() {
        return "List your todo items with their id, status and detail.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "todo_list error: no todo store";
        }
        String statusArg = args.get("status") == null ? "all"
                : String.valueOf(args.get("status")).strip().toLowerCase(Locale.ROOT);
        if (statusArg.isEmpty()) {
            statusArg = "all";
        }
        List<TodoStore.Item> items = store.items();
        List<TodoStore.Item> shown = new ArrayList<>();
        for (TodoStore.Item it : items) {
            if (statusArg.equals("all") || statusArg.equals(it.status)) {
                shown.add(it);
            }
        }
        String head = "todo_list: " + items.size() + " item(s), showing " + shown.size()
                + (statusArg.equals("all") ? "" : ", status=" + statusArg);
        if (shown.isEmpty()) {
            return head + "\n(empty)";
        }
        StringBuilder sb = new StringBuilder(head).append('\n');
        int n = 0;
        for (TodoStore.Item it : shown) {
            n++;
            sb.append(n).append(". [").append(it.id).append("] ").append(it.status)
                    .append(" | ").append(Text.truncate(it.title, 120));
            if (it.detail != null && !it.detail.isBlank()) {
                sb.append("\n   ").append(Text.truncate(it.detail.replace('\n', ' '), 300));
            }
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
