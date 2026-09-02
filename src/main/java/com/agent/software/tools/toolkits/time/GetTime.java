package com.agent.software.tools.toolkits.time;

import com.agent.software.event.TimeEventBus;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * get_time — view the current schedule time (current tick count and schedule state).
 * Time rules: 1 tick = 10 minutes, system start = tick 0, off work at tick 60 each day.
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
