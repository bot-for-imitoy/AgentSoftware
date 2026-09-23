package com.agent.software.tools.toolkits.task;

import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** update_task：改一条"还没到点"的排期任务（内容 / 时间 / 优先级 / 目标）。 */
public class UpdateTask extends Tool {

    private final Role role;

    public UpdateTask(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "update_task";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("task_id", "The task_id shown by list_tasks (8-char id or the full id).");
        schema.put("content", "(Optional) new task text.");
        schema.put("in_minutes", TaskSupport.intProp("(Optional) reschedule to N simulated minutes from now."));
        schema.put("day", TaskSupport.intProp("(Optional) new absolute day number."));
        schema.put("tick", TaskSupport.intProp("(Optional) new tick inside that day's shift (0 = 08:00, 36000 = 18:00)."));
        schema.put("target", "(Optional) new assignee role_id or name.");
        schema.put("priority", "(Optional) LOW / NORMAL / HIGH / EMERGENCY.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Change a task that is scheduled but not due yet: its text, its time, its priority or its "
                + "assignee. Only not-yet-due tasks can be changed (see list_tasks); a task that is already "
                + "queued or finished cannot be edited.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "update_task error: role not bound to a system";
        }
        Task task;
        try {
            task = TaskSupport.findScheduled(role.getSystem().getEventBus(),
                    TaskSupport.str(args.get("task_id")));
            TaskSupport.requireManageable(role, task);
        } catch (IllegalArgumentException e) {
            return "update_task error: " + e.getMessage();
        }
        List<String> changes = new ArrayList<>();
        if (args.get("content") != null) {
            String content = TaskSupport.str(args.get("content")).strip();
            if (content.isEmpty()) {
                return "update_task error: content must not be empty";
            }
            task.content = content;
            changes.add("content");
        }
        if (args.get("priority") != null) {
            Priority p = TaskSupport.priority(args.get("priority"));
            task.priority = p;
            changes.add("priority=" + p);
        }
        boolean timeGiven = args.get("in_minutes") != null || args.get("tick") != null || args.get("day") != null;
        try {
            if (args.get("target") != null && !TaskSupport.str(args.get("target")).isBlank()) {
                Role target = TaskSupport.resolveTarget(role, TaskSupport.str(args.get("target")));
                task.targetRoleId = target.roleId;
                changes.add("target=" + target.roleId);
            }
            if (timeGiven) {
                Integer inMinutes = TaskSupport.toInt(args.get("in_minutes"));
                if (args.get("in_minutes") != null && inMinutes == null) {
                    return "update_task error: in_minutes is not an integer";
                }
                Integer day = TaskSupport.toInt(args.get("day"));
                if (args.get("day") != null && day == null) {
                    return "update_task error: day is not an integer";
                }
                Integer tick = TaskSupport.toInt(args.get("tick"));
                if (args.get("tick") != null && tick == null) {
                    return "update_task error: tick is not an integer";
                }
                TaskSupport.Slot slot = TaskSupport.resolveTime(role.getSystem().getTimeBus(),
                        inMinutes, day, tick, args.get("day") != null);
                task.targetTime = slot.tick();
                changes.add("time=" + slot.when());
            }
        } catch (IllegalArgumentException e) {
            return "update_task error: " + e.getMessage();
        }
        if (changes.isEmpty()) {
            return "update_task error: nothing to change (give content / priority / target / in_minutes / day+tick)";
        }
        // targetTime 可能变了：先摘下来再按新时间挂回去（TreeMap 的 key 必须跟着变）
        role.getSystem().getEventBus().cancel(task.uuid);
        role.getSystem().getEventBus().schedule(task);
        role.journal("update_task " + TaskSupport.shortId(task.uuid) + ": " + String.join(", ", changes));
        return "update_task: task " + TaskSupport.shortId(task.uuid) + " updated ("
                + String.join(", ", changes) + "). Now: "
                + TaskSupport.describeTime(role.getSystem().getTimeBus(), task.targetTime);
    }
}
