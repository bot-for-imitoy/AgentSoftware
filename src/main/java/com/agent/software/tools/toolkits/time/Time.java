package com.agent.software.tools.toolkits.time;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** 时间工具包：get_time / take_rest。 */
public class Time extends Toolkit {

    public Time(Role role) {
        addTool(new GetTime(role));
        addTool(new TakeRest(role));
    }

    @Override
    public String getDescription() {
        return "Time: get_time (current simulated time), take_rest (idle until the next event)";
    }
}
