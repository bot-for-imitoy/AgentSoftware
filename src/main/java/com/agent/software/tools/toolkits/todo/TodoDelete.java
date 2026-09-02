package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * todo_delete — delete a todo item (remove it from the list).
 */
public class TodoDelete extends Tool {

    private final TodoStore todoStore;

    public TodoDelete(TodoStore todoStore) {
        super();
        this.todoStore = todoStore;
    }

    @Override
    public String getToolName() {
        return "todo_delete";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("todo_id", "The todo id (from todo_list).");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object otodoId = args.get("todo_id");
        if (!(otodoId instanceof String)) {
            return otodoId == null
                    ? "todo_delete: Error: needs todo_id"
                    : "todo_delete: Error: todo_id is not a string";
        }
        String todoId = ((String) otodoId).strip();
        if (todoId.isEmpty()) {
            return "todo_delete: Error: needs todo_id";
        }
        if (this.todoStore.delete(todoId)) {
            return "todo_delete: Todo deleted: " + todoId;
        }
        return "todo_delete: Todo not found: " + todoId;
    }
}
