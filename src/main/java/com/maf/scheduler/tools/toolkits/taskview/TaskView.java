package com.maf.scheduler.tools.toolkits.taskview;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.tools.Toolkit;

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
        return "任务列表工具类: 查看分配给我的任务 (待处理队列 + 最近完成/失败历史)";
    }

}
