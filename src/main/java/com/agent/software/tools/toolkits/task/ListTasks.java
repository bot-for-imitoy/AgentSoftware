package com.agent.software.tools.toolkits.task;

import com.agent.software.event.Task;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** list_tasks：查看"还没到点"的排期任务（自己收到的 / 自己建的 / 全大组的）。 */
public class ListTasks extends Tool {

    private static final int LIMIT = 50;

    private final Role role;

    public ListTasks(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "list_tasks";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("scope", "(Optional) mine (default, scheduled for me) / created (I scheduled them) / all (whole cohort).");
        return schema;
    }

    @Override
    public String getDescription() {
        return "List the tasks that are scheduled but not due yet (with their task_id, target and time). "
                + "Tasks that are already due have moved to the assignee's queue — see my_tasks for those.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "list_tasks error: role not bound to a system";
        }
        String scope = TaskSupport.str(args.get("scope")).trim().toLowerCase();
        if (scope.isEmpty()) {
            scope = "mine";
        }
        List<Task> all = TaskSupport.scheduledTasks(role.getSystem().getEventBus());
        List<Task> hits = new ArrayList<>();
        for (Task t : all) {
            switch (scope) {
                case "mine" -> {
                    if (role.roleId.equals(t.targetRoleId)) {
                        hits.add(t);
                    }
                }
                case "created" -> {
                    if (role.roleId.equals(t.fromRoleId)) {
                        hits.add(t);
                    }
                }
                case "all" -> hits.add(t);
                default -> {
                    return "list_tasks error: scope must be mine / created / all, got '" + scope + "'";
                }
            }
        }
        if (hits.isEmpty()) {
            return "list_tasks (" + scope + "): no scheduled tasks";
        }
        StringBuilder sb = new StringBuilder("list_tasks (" + scope + "): " + hits.size()
                + " scheduled task(s), earliest first\n");
        for (int i = 0; i < hits.size() && i < LIMIT; i++) {
            sb.append(TaskSupport.line(hits.get(i), role.getSystem().getTimeBus())).append('\n');
        }
        if (hits.size() > LIMIT) {
            sb.append("... ").append(hits.size() - LIMIT).append(" more\n");
        }
        return sb.toString().stripTrailing();
    }
}
