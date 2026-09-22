package com.agent.software.tools.toolkits.pc;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** 个人电脑工具包：run_command / computer_status / reboot / lan_devices。 */
public class Pc extends Toolkit {

    public Pc(Role role) {
        addTool(new RunCommand(role));
        addTool(new ComputerStatus(role));
        addTool(new Reboot(role));
        addTool(new LanDevices(role == null || role.getSystem() == null
                ? null : role.getSystem().getComputerManager()));
    }

    @Override
    public String getDescription() {
        return "Personal computer: run_command / computer_status / reboot / lan_devices";
    }
}
