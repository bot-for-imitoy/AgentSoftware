package com.agent.software.tools.toolkits.client;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** 客户沟通工具包（只给管理组成员）：talk_to_client。 */
public class Client extends Toolkit {

    private final Role role;

    public Client(Role role) {
        this.role = role;
        addTool(new TalkToClient(role));
    }

    @Override
    public String getDescription() {
        return "Client communication: talk_to_client (the client is a real person)";
    }
}
