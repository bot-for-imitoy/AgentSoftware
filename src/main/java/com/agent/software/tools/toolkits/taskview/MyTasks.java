package com.agent.software.tools.toolkits.taskview;

import com.agent.software.event.Event;
import com.agent.software.event.Task;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** my_tasks：查看自己队列里还没处理的（事件/任务）。 */
public class MyTasks extends Tool {

    private final Role role;

    public MyTasks(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "my_tasks";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "List the events/tasks still queued for me.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null) {
            return "my_tasks error: no role";
        }
        StringBuilder sb = new StringBuilder("my_tasks: queue depth " + role.queueDepth() + "\n");
        Event head = role.peekEvent();
        if (head != null) {
            sb.append("next: ").append(head.type).append(" / ").append(head.priority)
                    .append(" from ").append(head.fromRoleId == null ? "-" : head.fromRoleId)
                    .append(head instanceof Task t ? (" / status " + t.status) : "")
                    .append(" / ").append(head.content == null ? "" : head.content);
        } else {
            sb.append("no pending work");
        }
        return sb.toString();
    }
}
