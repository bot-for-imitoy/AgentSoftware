package com.agent.software.tools.toolkits.taskview;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;

/**
 * 任务列表工具类 (Task View Toolkit) — 查看分配给我的任务 (队列 + 历史).
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
