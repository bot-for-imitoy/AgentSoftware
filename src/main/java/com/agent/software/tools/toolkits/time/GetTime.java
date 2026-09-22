package com.agent.software.tools.toolkits.time;

import com.agent.software.event.TimeBus;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** get_time：返回当前模拟日期/时间/tick。 */
public class GetTime extends Tool {

    private final Role role;

    public GetTime(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "get_time";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String getDescription() {
        return "Get the current simulated date/time and shift progress.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "get_time error: role not bound to a system";
        }
        TimeBus tb = role.getSystem().getTimeBus();
        return "Now: " + tb.currentDateTime()
                + " (day " + tb.getDay() + ", tick " + tb.now()
                + ", working hours: " + tb.isWorkingHours() + ")";
    }
}
