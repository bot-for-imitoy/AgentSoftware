package com.agent.software.tools.toolkits.taskview;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;

/**
 * Task list toolkit (Task View Toolkit) — view tasks assigned to me (queue + history).
 */
public class TaskView extends Toolkit {

    private final AgentRole agentRole;

    public TaskView(AgentRole agentRole) {
        this.agentRole = agentRole;
        addTool(new MyTasks(agentRole));
    }

    @Override
    public String getDescription(){
        return "Task list toolkit: view tasks assigned to me (pending queue + recent completed/failed history)";
    }

}
