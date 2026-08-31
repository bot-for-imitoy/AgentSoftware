package com.maf.scheduler.tools.toolkits.client;

import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.tools.Toolkit;

/**
 * 甲方交流工具类 (Client Toolkit) — 与甲方/用户实时交流 (控制台交互):
 * talk_to_client.
 */
public class Client extends Toolkit {

    private final AgentRole agentRole;

    public Client(AgentRole agentRole) {
        this.agentRole = agentRole;
        addTool(new TalkToClient());
    }

    @Override
    public String getDescription(){
        return "甲方交流工具类: 与甲方(用户)实时交流";
    }

}
