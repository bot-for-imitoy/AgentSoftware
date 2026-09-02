package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * todo_add — add a todo item to your own Todo list (returns the todo id).
 */
public class TodoAdd extends Tool {

    private final TodoStore todoStore;

    public TodoAdd(TodoStore todoStore) {
        super();
        this.todoStore = todoStore;
    }

    @Override
    public String getToolName() {
        return "todo_add";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("title", "The title of the todo item.");
        schema.put("detail", "(Optional) Detail of the todo item.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object otitle = args.get("title");
        if (!(otitle instanceof String)) {
            return otitle == null
                    ? "todo_add: Error: needs a todo title"
                    : "todo_add: Error: title is not a string";
        }
        String title = ((String) otitle).strip();
        if (title.isEmpty()) {
            return "todo_add: Error: needs a todo title";
        }
        Object odetail = args.get("detail");
        String detail = odetail instanceof String s ? s.strip() : "";
        Map<String, Object> item = this.todoStore.add(title, detail);
        return "todo_add: Added todo [ID=" + item.get("id") + "]: " + item.get("title")
                + " (status pending)";
    }
}
