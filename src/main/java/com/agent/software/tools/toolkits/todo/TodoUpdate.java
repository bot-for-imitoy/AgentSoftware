package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * todo_update — update the status of a todo item:
 * pending (not started) → in_progress (in progress) → completed (completed).
 */
public class TodoUpdate extends Tool {

    private final TodoStore todoStore;

    public TodoUpdate(TodoStore todoStore) {
        super();
        this.todoStore = todoStore;
    }

    @Override
    public String getToolName() {
        return "todo_update";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("todo_id", "The todo id (from todo_list).");
        schema.put("status", "New status: pending / in_progress / completed.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object otodoId = args.get("todo_id");
        Object ostatus = args.get("status");
        if (!(otodoId instanceof String)) {
            return otodoId == null
                    ? "todo_update: Error: needs todo_id"
                    : "todo_update: Error: todo_id is not a string";
        }
        if (!(ostatus instanceof String)) {
            return ostatus == null
                    ? "todo_update: Error: needs status"
                    : "todo_update: Error: status is not a string";
        }
        String todoId = ((String) otodoId).strip();
        String status = ((String) ostatus).strip();
        if (todoId.isEmpty()) {
            return "todo_update: Error: needs todo_id";
        }
        if (status.isEmpty()) {
            return "todo_update: Error: needs status";
        }
        Map<String, Object> item;
        try {
            item = this.todoStore.update(todoId, status);
        } catch (IllegalArgumentException exc) {
            return "todo_update: Error: " + exc.getMessage();
        }
        if (item == null) {
            return "todo_update: Todo not found: " + todoId;
        }
        return "todo_update: Todo updated [ID=" + todoId + "]: " + item.get("title")
                + " → " + item.get("status");
    }
}
