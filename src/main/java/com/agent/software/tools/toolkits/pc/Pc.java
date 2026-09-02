package com.agent.software.tools.toolkits.pc;

import com.agent.software.computers.Computer;
import com.agent.software.computers.ComputerManager;
import com.agent.software.role.AgentRole;

import com.agent.software.tools.Toolkit;

/**
 * Personal computer toolkit class (Pc Toolkit = Computer tools) - lets the LLM work on its own computer:
 * run_command / computer_status / lan_devices / reboot.
 *
 * Each role has its own computer (a Podman virtual computer by default); the computer turns on when the role
 * joins/starts, and turns off automatically at the end of the day (off-duty summary) or when the role leaves.
 */
public class Pc extends Toolkit {

    private final Computer computer;

    public Pc(Computer computer) {
        this(computer, null);
    }

    public Pc(Computer computer, ComputerManager manager) {
        this.computer = computer;
        addTool(new RunCommand(computer));
        addTool(new ComputerStatus(computer));
        addTool(new LanDevices(manager));
        addTool(new Reboot(computer));
    }

    public Pc(AgentRole agentRole) {
        this(agentRole.computer(), agentRole.computerManager());
    }

    @Override
    public String getDescription(){
        return "Personal computer toolkit (pc): run commands, view computer status, view LAN devices, reboot computer";
    }

}
