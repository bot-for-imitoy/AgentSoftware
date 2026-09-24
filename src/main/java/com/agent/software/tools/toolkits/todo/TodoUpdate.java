package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** todo_update：改当前基线组里某条事项的状态/标题/描述（改完立刻落盘）。 */
public class TodoUpdate extends Tool {

    private final TodoStore store;

    public TodoUpdate(TodoStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "todo_update";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("todo_id", "The 8-char id shown by todo_list (a unique prefix is enough).");
        schema.put("status", "(Optional) pending / in_progress / completed.");
        schema.put("title", "(Optional) new title.");
        schema.put("detail", "(Optional) new detail.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Update one todo item of your current baseline group: mark it in_progress / completed, "
                + "or fix its title and detail. The change is saved immediately.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "todo_update error: no todo store";
        }
        String id = args.get("todo_id") == null ? "" : String.valueOf(args.get("todo_id")).strip();
        if (id.isEmpty()) {
            return "todo_update error: needs a todo_id (see todo_list)";
        }
        String status = args.get("status") == null ? null : String.valueOf(args.get("status"));
        String title = args.get("title") == null ? null : String.valueOf(args.get("title"));
        String detail = args.get("detail") == null ? null : String.valueOf(args.get("detail"));
        if ((status == null || status.isBlank()) && (title == null || title.isBlank())
                && (detail == null || detail.isBlank())) {
            return "todo_update error: nothing to change (give status / title / detail)";
        }
        TodoStore.Item item = store.update(id, status, title, detail);
        if (item == null) {
            return "todo_update error: no unique item '" + id + "' in group "
                    + store.currentGroup() + " (see todo_list; use todo_group_switch first if it is in another group)";
        }
        return "todo_update: [" + item.id + "] " + item.status + " | " + item.title
                + " (group " + store.currentGroup() + ")";
    }
}
