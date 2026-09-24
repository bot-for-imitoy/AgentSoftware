package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** todo_delete：删掉当前基线组里的一条事项。 */
public class TodoDelete extends Tool {

    private final TodoStore store;

    public TodoDelete(TodoStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "todo_delete";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("todo_id", "The 8-char id shown by todo_list (a unique prefix is enough).");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Delete one todo item from your current baseline group.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "todo_delete error: no todo store";
        }
        String id = args.get("todo_id") == null ? "" : String.valueOf(args.get("todo_id")).strip();
        if (id.isEmpty()) {
            return "todo_delete error: needs a todo_id (see todo_list)";
        }
        if (!store.delete(id)) {
            return "todo_delete error: no unique item '" + id + "' in group "
                    + store.currentGroup() + " (see todo_list)";
        }
        return "todo_delete: removed [" + id + "] from group " + store.currentGroup();
    }
}
