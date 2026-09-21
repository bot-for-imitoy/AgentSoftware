package com.agent.software.tools.toolkits.time;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * Time toolkit — schedule time viewing and rest.
 */
public class Time extends Toolkit {

    private final TimeEventBus timeManager;
    private final Role role;

    public Time(TimeEventBus timeManager, Role role) {
        this.timeManager = timeManager;
        this.role = role;
        addTool(new GetTime(timeManager));
        addTool(new TakeRest(role));
    }

    public Time(Role role) {
        this(role.timeManager(), role);
    }

    @Override
    public String getDescription(){
        return "Time toolkit: check the current schedule time (get_time), take a rest (take_rest)";
    }

}
