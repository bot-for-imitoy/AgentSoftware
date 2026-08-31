package com.maf.scheduler.tools.toolkits.time;

import com.maf.scheduler.core.TimeEventBus;
import com.maf.scheduler.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * get_time — 查看当前作息时间 (当前 Tick 数和作息状态).
 * 时间规则: 1 Tick = 10 分钟, 系统启动 = Tick 0, 每天第 60 Tick 下班.
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
        return this.timeManager.describe() + "\n当前 Tick 数: " + this.timeManager.currentTick();
    }
}
