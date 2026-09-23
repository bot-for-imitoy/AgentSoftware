package com.agent.software.tools.toolkits.task;

import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * create_task：把一条任务排到未来的某个时刻。
 *
 * <p>到点后它会作为一条普通任务投递到目标角色的队列，把对方唤醒 —— 这就是"给自己/同事
 * 定个未来提醒"的正确做法（不要靠一遍遍 take_rest 空转等时间）。
 */
public class CreateTask extends Tool {

    private final Role role;

    public CreateTask(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "create_task";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("content", "What the task is about; this text is delivered to the assignee as the task input.");
        schema.put("in_minutes", TaskSupport.intProp(
                "(Optional) schedule N simulated minutes from now. Example: 90."));
        schema.put("day", TaskSupport.intProp(
                "(Optional) absolute simulated day number (day 1 = the first workday). Default: today."));
        schema.put("tick", TaskSupport.intProp(
                "(Optional) tick inside that day's shift: 0 = 08:00, 36000 = 18:00. Default: 0 when day is given."));
        schema.put("target", "(Optional) role_id or name of the assignee (must be in the cohort). Default: yourself.");
        schema.put("priority", "(Optional) LOW / NORMAL / HIGH / EMERGENCY. Default: NORMAL.");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Schedule a task for the future (for yourself by default, or for a colleague in the same team; "
                + "the management group may assign across teams). The task is delivered at the given simulated "
                + "time and wakes the assignee up. Give the time either as in_minutes, or as day + tick. "
                + "Use this instead of idling in a loop when you need to act at a later time.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "create_task error: role not bound to a system";
        }
        String content = TaskSupport.str(args.get("content")).strip();
        if (content.isEmpty()) {
            return "create_task error: needs task content";
        }
        Integer inMinutes = TaskSupport.toInt(args.get("in_minutes"));
        if (args.get("in_minutes") != null && inMinutes == null) {
            return "create_task error: in_minutes is not an integer";
        }
        Integer day = TaskSupport.toInt(args.get("day"));
        if (args.get("day") != null && day == null) {
            return "create_task error: day is not an integer";
        }
        Integer tick = TaskSupport.toInt(args.get("tick"));
        if (args.get("tick") != null && tick == null) {
            return "create_task error: tick is not an integer";
        }
        Priority priority = TaskSupport.priority(args.get("priority"));
        try {
            Role target = TaskSupport.resolveTarget(role, TaskSupport.str(args.get("target")));
            TaskSupport.Slot slot = TaskSupport.resolveTime(role.getSystem().getTimeBus(),
                    inMinutes, day, tick, args.get("day") != null);
            Task task = new Task(role.roleId, target.roleId, slot.tick(), content, priority);
            role.getSystem().getEventBus().schedule(task);
            role.journal("create_task " + TaskSupport.shortId(task.uuid) + " for " + target.roleId
                    + " at " + slot.when());
            return "create_task: scheduled task " + TaskSupport.shortId(task.uuid)
                    + " for " + target.roleId + " (" + target.name + ") at " + slot.when()
                    + ", priority " + priority + ". It will wake them up when due.";
        } catch (IllegalArgumentException e) {
            return "create_task error: " + e.getMessage();
        }
    }
}
