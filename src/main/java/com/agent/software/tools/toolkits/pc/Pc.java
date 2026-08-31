package com.agent.software.tools.toolkits.pc;

import com.agent.software.computers.Computer;
import com.agent.software.role.AgentRole;

import com.agent.software.tools.Toolkit;

/**
 * 个人电脑工具类 (Pc Toolkit = Computer 工具) — 让 LLM 在自己电脑上工作:
 * run_command / computer_status / lan_devices / reboot.
 *
 * 每个角色有独立电脑 (默认 Podman 虚拟电脑), 电脑开机时机: 角色加入/启动时
 * 自动开机; 一天结束 (下班总结) 或离职时自动关机.
 */
public class Pc extends Toolkit {

    private final Computer computer;

    public Pc(Computer computer) {
        this.computer = computer;
        addTool(new RunCommand(computer));
        addTool(new ComputerStatus(computer));
        addTool(new LanDevices());
        addTool(new Reboot(computer));
    }

    public Pc(AgentRole agentRole) {
        this(agentRole.computer());
    }

    @Override
    public String getDescription(){
        return "Personal computer toolkit (pc): run commands, view computer status, view LAN devices, reboot computer";
    }

}
