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
        // 互斥锁优先取角色所属系统的实例 (每系统一把锁, 多系统互不阻塞),
        // 未绑定系统的独立角色回退到进程级默认单例
        ClientCommunicationLock lock = agentRole != null && agentRole.system() != null
                ? agentRole.system().clientLock : ClientCommunicationLock.getInstance();
        addTool(new TalkToClient(agentRole, lock));
    }

    @Override
    public String getDescription(){
        return "Client communication toolkit: communicate in real time with the client (user)";
    }

}
