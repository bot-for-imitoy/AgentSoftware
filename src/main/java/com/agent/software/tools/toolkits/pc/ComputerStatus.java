package com.agent.software.tools.toolkits.pc;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** computer_status：电脑型号/开关机状态。 */
public class ComputerStatus extends Tool {

    private final Role role;

    public ComputerStatus(Role role) {
        this.role = role;
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
    public String getDescription() {
        return "Show your computer kind and power state.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || !role.hasComputer()) {
            return "computer_status: no computer";
        }
        return "computer: " + role.getComputer().getClass().getSimpleName()
                + ", on=" + role.getComputer().isOn()
                + ", role=" + role.roleId;
    }
}
