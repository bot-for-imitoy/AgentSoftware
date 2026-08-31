package com.agent.software.tools.toolkits.pc;

import com.maf.scheduler.core.Computer;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * computer_status — 查看个人电脑的状态: 是否开机, 工作目录在哪里, 电脑类型.
 */
public class ComputerStatus extends Tool {

    private final Computer computer;

    public ComputerStatus(Computer computer) {
        super();
        this.computer = computer;
    }

    @Override
    public String getToolName() {
        return "computer_status";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        return this.computer.describe();
    }
}
