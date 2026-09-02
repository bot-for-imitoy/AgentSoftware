package com.agent.software.tools.toolkits.client;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;

/**
 * Client communication toolkit (Client Toolkit) — real-time communication with the client/user (console interaction):
 * talk_to_client.
 */
public class Client extends Toolkit {

    private final AgentRole agentRole;

    public Client(AgentRole agentRole) {
        this.agentRole = agentRole;
        // The mutex lock prefers the instance of the system the role belongs to (one lock per system, multiple systems do not block each other);
        // standalone roles not bound to a system fall back to the process-level default singleton
        ClientCommunicationLock lock = agentRole != null && agentRole.system() != null
                ? agentRole.system().clientLock : ClientCommunicationLock.getInstance();
        addTool(new TalkToClient(agentRole, lock));
    }

    @Override
    public String getDescription(){
        return "Client communication toolkit: communicate in real time with the client (user)";
    }

}
