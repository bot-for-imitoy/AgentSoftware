package com.agent.software.tools.toolkits.client;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;

/**
 * 甲方交流工具类 (Client Toolkit) — 与甲方/用户实时交流 (控制台交互):
 * talk_to_client.
 */
public class Client extends Toolkit {

    private final AgentRole agentRole;

    public Client(AgentRole agentRole) {
        this.agentRole = agentRole;
        addTool(new TalkToClient(agentRole));
    }

    @Override
    public String getDescription(){
        return "Client communication toolkit: communicate in real time with the client (user)";
    }

}
