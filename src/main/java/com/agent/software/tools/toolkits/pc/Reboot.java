package com.agent.software.tools.toolkits.pc;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** reboot：重启自己电脑。 */
public class Reboot extends Tool {

    private final Role role;

    public Reboot(Role role) {
        this.role = role;
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
    public String getDescription() {
        return "Reboot your own personal computer.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || !role.hasComputer()) {
            return "reboot error: no computer";
        }
        role.getComputer().reboot();
        return "reboot: done (on=" + role.getComputer().isOn() + ")";
    }
}
