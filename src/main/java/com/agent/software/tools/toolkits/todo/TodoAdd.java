package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** todo_add：往某个 todo 组里加一条事项（不写 group 就加到当前基线组）。 */
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
        schema.put("group", "(Optional) which todo group to add to; default = your current baseline group. "
                + "A group that does not exist yet is created.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Add a todo item to one of your todo groups. Items are saved immediately. "
                + "Use todo_group_switch to change which group is your baseline.";
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
        String groupArg = args.get("group") == null ? "" : String.valueOf(args.get("group"));
        String group = store.resolveGroup(groupArg);
        TodoStore.Item item = store.add(title, detail, groupArg);
        return "todo_add: [" + item.id + "] " + item.title + " (group " + group
                + ", status " + item.status + ")";
    }
}
