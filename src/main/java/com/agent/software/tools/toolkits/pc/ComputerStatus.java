package com.agent.software.tools.toolkits.pc;


import com.agent.software.computers.Computer;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * computer_status - view the status of the personal computer: whether it is on, where the working directory is, and the computer type.
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
