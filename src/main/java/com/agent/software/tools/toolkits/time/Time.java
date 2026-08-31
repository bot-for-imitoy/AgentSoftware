package com.agent.software.tools.toolkits.time;

import com.agent.software.role.AgentRole;
import com.agent.software.event.TimeEventBus;
import com.agent.software.tools.Toolkit;

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
        return "Time toolkit: check the current schedule time (get_time), take a rest (take_rest)";
    }

}
