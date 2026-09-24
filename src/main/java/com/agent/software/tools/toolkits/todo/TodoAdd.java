package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** todo_add：往自己的待办清单里加一条事项（清单是平铺的，没有组）。 */
public class TodoAdd extends Tool {

    private final TodoStore store;

    public TodoAdd(TodoStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "todo_add";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("title", "Short title of the todo item.");
        schema.put("detail", "(Optional) longer description / acceptance criteria.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Add a todo item to your own todo list. It is saved immediately.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "todo_add error: no todo store";
        }
        String title = args.get("title") == null ? "" : String.valueOf(args.get("title")).strip();
        if (title.isEmpty()) {
            return "todo_add error: needs a title";
        }
        String detail = args.get("detail") == null ? "" : String.valueOf(args.get("detail"));
        TodoStore.Item item = store.add(title, detail);
        return "todo_add: [" + item.id + "] " + item.title + " (status " + item.status
                + ", " + store.count() + " item(s))";
    }
}
