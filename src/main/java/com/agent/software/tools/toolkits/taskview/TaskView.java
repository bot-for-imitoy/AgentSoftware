package com.agent.software.tools.toolkits.taskview;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * Task list toolkit (Task View Toolkit) — view tasks assigned to me (queue + history).
 */
public class TaskView extends Toolkit {

    private final Role role;

    public TaskView(Role role) {
        this.role = role;
        addTool(new MyTasks(role));
    }

    @Override
    public String getDescription(){
        return "Task list toolkit: view tasks assigned to me (pending queue + recent completed/failed history)";
    }

}
