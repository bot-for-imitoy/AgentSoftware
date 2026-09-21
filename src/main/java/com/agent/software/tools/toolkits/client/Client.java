package com.agent.software.tools.toolkits.client;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * Client communication toolkit (Client Toolkit) — real-time communication with the client/user (console interaction):
 * talk_to_client.
 */
public class Client extends Toolkit {

    private final Role role;

    public Client(Role role) {
        this.role = role;
        // The mutex lock prefers the instance of the system the role belongs to (one lock per system, multiple systems do not block each other);
        // standalone roles not bound to a system fall back to the process-level default singleton
        ClientCommunicationLock lock = role != null && role.system() != null
                ? role.system().clientLock : ClientCommunicationLock.getInstance();
        addTool(new TalkToClient(role, lock));
    }

    @Override
    public String getDescription(){
        return "Client communication toolkit: communicate in real time with the client (user)";
    }

}
