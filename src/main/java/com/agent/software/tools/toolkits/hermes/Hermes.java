package com.agent.software.tools.toolkits.hermes;

import com.agent.software.computers.Computer;
import com.agent.software.role.AgentRole;

import com.agent.software.tools.Toolkit;

/**
 * Hermes 工具类 (Hermes Toolkit) — 调用角色电脑 (容器) 上安装的 Hermes Agent:
 * hermes_new_conversation / hermes_send.
 */
public class Hermes extends Toolkit {

    private final Computer computer;

    public Hermes(Computer computer) {
        this.computer = computer;
        addTool(new HermesNewConversation(computer));
        addTool(new HermesSend(computer));
    }

    public Hermes(AgentRole agentRole) {
        this(agentRole.computer());
    }

    @Override
    public String getDescription(){
        return "Hermes toolkit: create a new conversation in the Hermes Agent on the computer / send content and get a reply";
    }

}
