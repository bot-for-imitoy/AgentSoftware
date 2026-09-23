package com.agent.software.tools.toolkits.task;

import com.agent.software.event.Task;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** delete_task：取消一条"还没到点"的排期任务。 */
public class DeleteTask extends Tool {

    private final Role role;

    public DeleteTask(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "delete_task";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("task_id", "The task_id shown by list_tasks (8-char id or the full id).");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Cancel a task that is scheduled but not due yet. Only not-yet-due tasks can be cancelled; "
                + "a task that is already queued or finished cannot be deleted.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "delete_task error: role not bound to a system";
        }
        Task task;
        try {
            task = TaskSupport.findScheduled(role.getSystem().getEventBus(),
                    TaskSupport.str(args.get("task_id")));
            TaskSupport.requireManageable(role, task);
        } catch (IllegalArgumentException e) {
            return "delete_task error: " + e.getMessage();
        }
        String when = TaskSupport.describeTime(role.getSystem().getTimeBus(), task.targetTime);
        boolean removed = role.getSystem().getEventBus().cancel(task.uuid);
        if (!removed) {
            return "delete_task error: task " + TaskSupport.shortId(task.uuid)
                    + " was already delivered or cancelled";
        }
        role.journal("delete_task " + TaskSupport.shortId(task.uuid) + " (was due " + when + ")");
        return "delete_task: task " + TaskSupport.shortId(task.uuid) + " cancelled (it was due "
                + when + ", target " + task.targetRoleId + ")";
    }
}
