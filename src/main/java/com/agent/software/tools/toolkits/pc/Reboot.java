package com.agent.software.tools.toolkits.pc;


import com.agent.software.computers.Computer;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * reboot - restart the personal computer (powers off then on automatically). Useful for clearing runtime state or restarting after installing tools.
 */
public class Reboot extends Tool {

    private final Computer computer;

    public Reboot(Computer computer) {
        super();
        this.computer = computer;
    }

    @Override
    public String getToolName() {
        return "reboot";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        return this.computer.reboot();
    }
}
