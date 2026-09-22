package com.agent.software.io;

import com.agent.software.tools.toolkits.client.ClientCommunicationLock;
import com.agent.software.web.ChatStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebInput tests: reads the client reply typed into the Web page input box.
 *
 * Prerequisites enforced by {@code read}: an attached Web frontend (heartbeat) + a holder of the
 * client-communication lock (the member waiting for the reply).
 */
class WebInputTest {

    @AfterEach
    void clearTimeoutProperty() {
        System.clearProperty("AGENTSOFTWARE_CLIENT_REPLY_TIMEOUT");
    }

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
    void marksItselfAsWebPageChannel() {
        assertTrue(new WebInput().isWebPage());
        assertFalse(new StdInput().isWebPage());
    }

    @Test
    void roundtripReadsPageReply() throws Exception {
        ChatStore store = new ChatStore();
        ClientCommunicationLock lock = new ClientCommunicationLock();
        WebInput input = new WebInput(store, lock);
        store.markAttached();                 // a browser is polling the Web UI
        assertTrue(lock.tryAcquire("CEO", "Lin Zong") == null);  // CEO starts the dialogue

        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(input.read("Leadership Group")));
        t.start();

        try {
            assertTrue(waitUntil(store::isClientWaitPending, 5000), "read should register the waiting state");
            assertEquals("CEO", store.pendingHolderRoleId());
            assertEquals("Lin Zong", store.pendingHolderName());

            store.postClientReply("Please develop a payment system for me");  // client replies on the page
            t.join(5000);
            assertFalse(t.isAlive());
            assertEquals("Please develop a payment system for me", result.get());
            assertFalse(store.isClientWaitPending(), "waiting state should be reset after the reply");
        } finally {
            t.join(1000);
            lock.release("CEO");
        }
    }

    @Test
    void errorsWhenWebUiNotAttached() {
        ChatStore store = new ChatStore();
        ClientCommunicationLock lock = new ClientCommunicationLock();
        WebInput input = new WebInput(store, lock);   // never marked attached
        assertTrue(lock.tryAcquire("CEO", "Lin Zong") == null);

        String result = input.read("Leadership Group");
        assertNotNull(result);
        assertTrue(Input.isError(result), "unattached Web input should return an error: " + result);
        assertTrue(result.contains("not attached"));
        assertFalse(store.isClientWaitPending());
        lock.release("CEO");
    }

    @Test
    void errorsWhenNoConversationIsActive() {
        ChatStore store = new ChatStore();
        ClientCommunicationLock lock = new ClientCommunicationLock();
        WebInput input = new WebInput(store, lock);
        store.markAttached();                       // Web attached but nobody holds the dialogue lock

        String result = input.read("Leadership Group");
        assertNotNull(result);
        assertTrue(Input.isError(result), "read without an active conversation should error: " + result);
        assertTrue(result.contains("no client conversation is active"));
        assertFalse(store.isClientWaitPending());
    }

    @Test
    void errorsWhenUnbound() {
        WebInput input = new WebInput();
        String result = input.read("Leadership Group");
        assertNotNull(result);
        assertTrue(Input.isError(result), "unbound Web input should error: " + result);
    }

    @Test
    void timesOutWhenClientNeverReplies() throws Exception {
        System.setProperty("AGENTSOFTWARE_CLIENT_REPLY_TIMEOUT", "300");
        ChatStore store = new ChatStore();
        ClientCommunicationLock lock = new ClientCommunicationLock();
        WebInput input = new WebInput(store, lock);
        store.markAttached();
        assertTrue(lock.tryAcquire("CEO", "Lin Zong") == null);

        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(input.read("Leadership Group")));
        t.start();

        try {
            assertTrue(waitUntil(store::isClientWaitPending, 5000), "read should register the waiting state");
            t.join(5000);   // client never replies → the 300ms timeout fires
            assertFalse(t.isAlive());
            assertNotNull(result.get());
            assertTrue(Input.isError(result.get()), "timeout should return an error: " + result.get());
            assertTrue(result.get().contains("did not reply within"));
            assertFalse(store.isClientWaitPending(), "waiting state should be reset after the timeout");
        } finally {
            t.join(1000);
            lock.release("CEO");
        }
    }
}
