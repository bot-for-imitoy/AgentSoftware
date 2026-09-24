package com.agent.software.tools.toolkits.todo;

import com.agent.software.store.TodoStore;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * todo_group_switch：切换"基线组"（组不存在就新建）。
 *
 * <p>切换后不带 {@code group} 参数的 {@code todo_add}/{@code todo_list}/{@code todo_update}/
 * {@code todo_delete} 都作用在这个组上；其它组的事项原样保留。
 */
public class TodoGroupSwitch extends Tool {

    private final TodoStore store;

    public TodoGroupSwitch(TodoStore store) {
        this.store = store;
    }

    @Override
    public String getToolName() {
        return "todo_group_switch";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "Group name to use as the baseline. It is created if it does not exist yet.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Switch your baseline todo group (creating it if needed). Subsequent todo_add / todo_list / "
                + "todo_update / todo_delete act on this group unless you pass an explicit group.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (store == null) {
            return "todo_group_switch error: no todo store";
        }
        String name = args.get("name") == null ? "" : String.valueOf(args.get("name")).strip();
        if (name.isEmpty()) {
            return "todo_group_switch error: needs a group name (see todo_group_list)";
        }
        boolean created = store.switchGroup(name);
        return "todo_group_switch: baseline is now '" + store.currentGroup() + "' ("
                + (created ? "created, " : "") + store.count(store.currentGroup()) + " item(s))";
    }
}
