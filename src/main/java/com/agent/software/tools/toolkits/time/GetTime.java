package com.agent.software.tools.toolkits.time;

import com.agent.software.event.TimeEventBus;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * get_time — view the current simulated schedule time (calendar date, clock and work-rest status).
 * Time rules: 1 tick = 1 simulated second (configurable), each day starts at 08:00:00 (tick 0)
 * and the shift ends at 18:00:00 (tick 36000 by default).
 */
public class GetTime extends Tool {

    private final TimeEventBus timeManager;

    public GetTime(TimeEventBus timeManager) {
        super();
        this.timeManager = timeManager;
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
    public String handler(Map<String, Object> args) {
        return this.timeManager.describe() + "\nCurrent Tick count: " + this.timeManager.currentTick();
    }
}
