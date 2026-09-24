package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** todo_group_list：列出所有 todo 组、各自事项数，以及哪个是当前基线组。 */
public class TodoGroupList extends Tool {

    private final TodoStore store;

    public TodoGroupList(TodoStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "todo_group_list";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "List your todo groups with their item counts and which one is your current baseline group.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "todo_group_list error: no todo store";
        }
        List<String> names = store.groups();
        String current = store.currentGroup();
        StringBuilder sb = new StringBuilder("todo_group_list: " + names.size()
                + " group(s), baseline = " + current + "\n");
        for (String name : names) {
            int done = 0;
            for (TodoStore.Item it : store.items(name)) {
                if ("completed".equals(it.status)) {
                    done++;
                }
            }
            sb.append("- ").append(name).append(" (").append(store.count(name)).append(" item(s), ")
                    .append(done).append(" completed)")
                    .append(name.equals(current) ? "  <- baseline" : "").append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
