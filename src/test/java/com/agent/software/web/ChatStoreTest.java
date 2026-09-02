package com.agent.software.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatStore tests: message recording/incremental pull + client dialogue coordination (Web UI data source).
 */
class ChatStoreTest {

    // ── Message recording ─────────────────────────────────────

    @Test
    void testRecordAndMessagesSince() {
        ChatStore store = new ChatStore();
        ChatStore.ChatMessage m1 = store.record("talk", "Leadership Group", "CEO", "Lin Zong", "COO", "Chen Zong", "Hello", "NORMAL");
        assertNotNull(m1);
        assertEquals(1L, m1.seq);
        store.record("talk", "Leadership Group", "COO", "Chen Zong", "CEO", "Lin Zong", "Received", null);
        store.record("client", "Leadership Group", "CEO", "Lin Zong", "", "Client A", "May I ask what the requirements are?", null);

        assertEquals(3, store.lastSeq());
        List<Map<String, Object>> all = store.messagesSince(0);
        assertEquals(3, all.size());
        assertEquals(1L, all.get(0).get("seq"));
        assertEquals("Lin Zong", all.get(0).get("fromName"));
        assertEquals("Client A", all.get(2).get("toName"));

        // incremental pull: since=2 → only seq=3 remains
        List<Map<String, Object>> inc = store.messagesSince(2);
        assertEquals(1, inc.size());
        assertEquals(3L, inc.get(0).get("seq"));
    }

    // ── Client dialogue coordination ─────────────────────────

    @Test
    void testClientReplyRoundtrip() throws Exception {
        ChatStore store = new ChatStore();
        assertFalse(store.isClientWaitPending());

        assertNull(store.beginClientWait("CEO", "Lin Zong", "Leadership Group"));
        assertTrue(store.isClientWaitPending());
        assertEquals("Lin Zong", store.pendingHolderName());

        // another member registering again → rejected
        String err = store.beginClientWait("COO", "Chen Zong", "Leadership Group");
        assertTrue(err != null && err.contains("currently waiting"));

        // the client submits a reply → the recorded message is returned and the waiter is woken
        ChatStore.ChatMessage m = store.postClientReply("Please develop a payment system for me");
        assertNotNull(m);
        assertEquals(ChatStore.KIND_CLIENT, m.kind);
        assertEquals("Client A", m.fromName);
        assertEquals("CEO", m.toRoleId);
        assertEquals("Leadership Group", m.group);

        String reply = store.awaitClientReply(5000);
        assertEquals("Please develop a payment system for me", reply);

        // state resets after the wait ends
        store.endClientWait();
        assertFalse(store.isClientWaitPending());
        assertNull(store.pendingHolderName());
        // replying again → no pending dialogue
        assertNull(store.postClientReply("Another one"));
    }

    @Test
    void testAwaitTimeout() throws Exception {
        ChatStore store = new ChatStore();
        store.beginClientWait("CEO", "Lin Zong", "Leadership Group");
        String reply = store.awaitClientReply(200);
        assertNull(reply, "timeout should return null");
        store.endClientWait();
    }

    @Test
    void testAttachHeartbeat() throws InterruptedException {
        ChatStore store = new ChatStore();
        assertFalse(store.isAttached());
        store.markAttached();
        assertTrue(store.isAttached());
        // stays attached within the TTL (do not sleep long; just verify the TTL constant is sane)
        assertTrue(ChatStore.ATTACH_TTL_MS >= TimeUnit.SECONDS.toMillis(30));
    }
}
