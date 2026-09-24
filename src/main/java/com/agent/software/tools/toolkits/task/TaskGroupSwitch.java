package com.agent.software.tools.toolkits.task;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * task_group_switch：切换任务看板的"基线组"（组不存在就新建）。
 *
 * <p>切换后不带 {@code group} 参数的 {@code create_task} / {@code list_tasks} 都作用在这个组上；
 * 其它组的任务原样保留。
 */
public class TaskGroupSwitch extends Tool {

    private final Role role;

    public TaskGroupSwitch(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "task_group_switch";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "Group name to use as the baseline. It is created if it does not exist yet.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Switch the baseline group of your task board (creating it if needed). Subsequent "
                + "create_task / list_tasks act on this group unless you pass an explicit group.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "task_group_switch error: role not bound to a system";
        }
        String name = args.get("name") == null ? "" : String.valueOf(args.get("name")).strip();
        if (name.isEmpty()) {
            return "task_group_switch error: needs a group name (see task_group_list)";
        }
        boolean created = role.taskBoard().switchGroup(name);
        String current = role.taskBoard().currentGroup();
        return "task_group_switch: baseline is now '" + current + "' ("
                + (created ? "created, " : "") + role.taskBoard().count(current) + " task(s))";
    }
}
