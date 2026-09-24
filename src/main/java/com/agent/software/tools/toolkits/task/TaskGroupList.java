package com.agent.software.tools.toolkits.task;

import com.agent.software.role.Role;
import com.agent.software.store.TaskBoard;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** task_group_list：列出任务看板上的所有组、各自任务数，以及哪个是当前基线组。 */
public class TaskGroupList extends Tool {

    private final Role role;

    public TaskGroupList(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "task_group_list";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "List your task groups with their task counts and which one is your current baseline group.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "task_group_list error: role not bound to a system";
        }
        TaskBoard board = role.taskBoard();
        List<String> names = board.groups();
        String current = board.currentGroup();
        StringBuilder sb = new StringBuilder("task_group_list: " + names.size()
                + " group(s), baseline = " + current + "\n");
        for (String name : names) {
            int done = 0;
            for (TaskBoard.Record r : board.items(name)) {
                if ("done".equals(r.status)) {
                    done++;
                }
            }
            sb.append("- ").append(name).append(" (").append(board.count(name)).append(" task(s), ")
                    .append(done).append(" done)")
                    .append(name.equals(current) ? "  <- baseline" : "").append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
