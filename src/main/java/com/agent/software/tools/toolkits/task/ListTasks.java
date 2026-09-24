package com.agent.software.tools.toolkits.task;

import com.agent.software.role.Role;
import com.agent.software.store.TaskBoard;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * list_tasks：看自己的**任务看板**——某个组里我派出去的任务及其状态（含已经跑完的）。
 *
 * <p>和 {@code my_tasks} 的分工：{@code my_tasks} 看"现在我手上要干的（队列 + 最近历史）"，
 * {@code list_tasks} 看"我派出去的活排在哪、做到哪一步了"。
 */
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
        schema.put("group", "(Optional) which task group to list; default = your current baseline group.");
        schema.put("status", "(Optional) pending / running / done / failed / all (default all).");
        return schema;
    }

    @Override
    public String getDescription() {
        return "List the tasks on your task board for one group, with their status (pending/running/done/"
                + "failed), target and due time. Tasks stay on the board after they finish, so this is how "
                + "you check whether a group's work is actually done.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "list_tasks error: role not bound to a system";
        }
        TaskBoard board = role.taskBoard();
        String group = board.resolveGroup(TaskSupport.str(args.get("group")));
        String statusArg = TaskSupport.str(args.get("status")).strip().toLowerCase(Locale.ROOT);
        if (statusArg.isEmpty()) {
            statusArg = "all";
        }
        List<TaskBoard.Record> all = board.items(group);
        List<TaskBoard.Record> shown = new ArrayList<>();
        for (TaskBoard.Record r : all) {
            if (statusArg.equals("all") || statusArg.equals(r.status)) {
                shown.add(r);
            }
        }
        String head = "list_tasks (group " + group + ", baseline " + board.currentGroup() + ": "
                + all.size() + " task(s), showing " + shown.size()
                + (statusArg.equals("all") ? ")" : ", status=" + statusArg + ")");
        if (shown.isEmpty()) {
            return head + "\n(empty)";
        }
        StringBuilder sb = new StringBuilder(head).append('\n');
        for (int i = 0; i < shown.size() && i < LIMIT; i++) {
            TaskBoard.Record r = shown.get(i);
            sb.append("- [").append(TaskSupport.shortId(r.id)).append("] ").append(r.status)
                    .append(" | ").append(r.due.isEmpty() ? "?" : r.due)
                    .append(" | -> ").append(r.target.isEmpty() ? "?" : r.target);
            if (r.tokens > 0) {
                sb.append(" | ").append(r.tokens).append(" tokens");
            }
            sb.append(" | ").append(Text.truncate(r.title == null ? "" : r.title.replace('\n', ' '), 160))
                    .append('\n');
        }
        if (shown.size() > LIMIT) {
            sb.append("... ").append(shown.size() - LIMIT).append(" more\n");
        }
        return sb.toString().stripTrailing();
    }
}
