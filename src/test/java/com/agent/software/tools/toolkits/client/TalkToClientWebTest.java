package com.agent.software.tools.toolkits.client;

import com.agent.software.AgentSystem;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.tools.Tool;
import com.agent.software.web.ChatStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * talk_to_client Web mode integration test: a leader calls → input enabled state (waiting for the client reply)
 * → the client submits a reply on the page → the leader gets it, and the chat log is complete.
 */
class TalkToClientWebTest {

    private static boolean waitUntil(java.util.function.Supplier<Boolean> pred, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(pred.get())) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    @Test
    void testWebModeRoundtrip() throws Exception {
        AgentSystem system = new AgentSystem(List.of(RoleLoader.getTemplate("CEO")), null, 30.0, false);
        AgentRole ceo = system.getRole("CEO");
        ChatStore store = system.chatStore;
        store.markAttached();   // simulate an attached Web frontend

        Tool talkToClient = new Client(ceo).getTools().stream()
                .filter(t -> "talk_to_client".equals(t.getToolName()))
                .findFirst().orElseThrow();

        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talkToClient.handler(Map.of("message", "Hello, may I ask what the project requirements are?"))));
        t.start();

        try {
            // the leader enters the "waiting for the client reply" state → the Web input box should be enabled
            assertTrue(waitUntil(store::isClientWaitPending, 5000), "leader did not enter the waiting-for-client-reply state");
            assertEquals("CEO", store.pendingHolderRoleId());
            assertEquals("Lin Zong", store.pendingHolderName());

            // the client replies on the page
            store.postClientReply("Please develop a payment system for me");

            t.join(5000);
            assertFalse(t.isAlive());
            assertTrue(result.get().contains("client reply: Please develop a payment system for me"));
            assertFalse(store.isClientWaitPending(), "the waiting state should be reset after the reply");

            // chat log: leader → client, client → leader (in the Leadership Group)
            List<Map<String, Object>> msgs = store.messagesSince(0);
            assertEquals(2, msgs.size());
            assertEquals("Lin Zong", msgs.get(0).get("fromName"));
            assertEquals("Client A", msgs.get(0).get("toName"));
            assertEquals("Client A", msgs.get(1).get("fromName"));
            assertEquals("Lin Zong", msgs.get(1).get("toName"));
            assertEquals("Leadership Group", msgs.get(0).get("group"));
            assertEquals("Leadership Group", msgs.get(1).get("group"));
        } finally {
            t.join(1000);
            system.stop();
        }
    }
}
