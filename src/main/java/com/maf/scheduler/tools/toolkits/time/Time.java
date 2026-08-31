package com.maf.scheduler.tools.toolkits.time;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.TimeEventBus;
import com.maf.scheduler.tools.Toolkit;

/**
 * 时间工具类 (Time Toolkit) — 作息时间查看与休息.
 */
public class Time extends Toolkit {

    private final TimeEventBus timeManager;
    private final AgentRole agentRole;

    public Time(TimeEventBus timeManager, AgentRole agentRole) {
        this.timeManager = timeManager;
        this.agentRole = agentRole;
        addTool(new GetTime(timeManager));
        addTool(new TakeRest(agentRole));
    }

    public Time(AgentRole agentRole) {
        this(agentRole.timeManager(), agentRole);
    }

    @Override
    public String getDescription(){
        return "时间工具类: 查看当前作息时间 (get_time), 休息 (take_rest)";
    }

}
