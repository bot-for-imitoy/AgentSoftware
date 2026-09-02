package com.agent.software.tools.toolkits.client;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;
import com.agent.software.tools.Toolkits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client communication tool tests: all Leadership Group members are equipped + global mutex.
 */
class ClientToolkitTest {

    private static boolean hasTalkToClient(List<Toolkit> toolkits) {
        return toolkits.stream().anyMatch(tk -> tk.getTools().stream()
                .anyMatch(t -> "talk_to_client".equals(t.getToolName())));
    }

    // ── Equipping: every Leadership Group member gets talk_to_client ─

    @Test
    void testLeadershipMembersGetClientToolkit() {
        for (String rid : new String[]{"CEO", "COO", "HR", "CTO", "business_analyst"}) {
            AgentRole leader = AgentRole.builder().name("Leader").roleId(rid)
                    .group(Toolkits.LEADERSHIP_GROUP).build();
            assertTrue(hasTalkToClient(Toolkits.defaultToolkits(leader)),
                    rid + " (Leadership Group) should be equipped with talk_to_client");
        }
    }

    @Test
    void testNonLeadershipMembersDoNotGetClientToolkit() {
        AgentRole dev = AgentRole.builder().name("Gu Chengyu").roleId("frontend_dev_1")
                .group("Frontend Development Group").build();
        assertFalse(hasTalkToClient(Toolkits.defaultToolkits(dev)),
                "non-leadership members should not be equipped with talk_to_client");

        AgentRole ungrouped = AgentRole.builder().name("Newcomer").roleId("newbie_1").build();
        assertFalse(hasTalkToClient(Toolkits.defaultToolkits(ungrouped)),
                "ungrouped roles should not be equipped with talk_to_client");
    }

    // ── Mutex lock ────────────────────────────────────────────

    @Test
    void testClientLockMutualExclusion() {
        ClientCommunicationLock lock = new ClientCommunicationLock();
        // acquirable when free
        assertNull(lock.tryAcquire("CEO", "Lin Zong"));
        assertTrue(lock.isHeld());
        // held by someone else → returns an error description
        String conflict = lock.tryAcquire("COO", "Chen Zong");
        assertTrue(conflict != null && conflict.contains("Lin Zong")
                && conflict.contains("currently talking to the client"));
        // re-acquiring by the same role is allowed (reentrant, does not deadlock itself)
        assertNull(lock.tryAcquire("CEO", "Lin Zong"));
        // releasing by a non-holder is a no-op
        lock.release("COO");
        assertTrue(lock.isHeld());
        // holder release → unlocked
        lock.release("CEO");
        assertFalse(lock.isHeld());
        // after release it can be acquired by others
        assertNull(lock.tryAcquire("COO", "Chen Zong"));
        lock.release("COO");
    }

    // ── handler: returns an error immediately when the lock is held (no blocking input) ─

    @Test
    void testTalkToClientRejectedWhileHeld() {
        ClientCommunicationLock lock = new ClientCommunicationLock();
        AgentRole a = AgentRole.builder().name("Lin Zong").roleId("CEO").build();
        AgentRole b = AgentRole.builder().name("Chen Zong").roleId("COO").build();
        TalkToClient ta = new TalkToClient(a, lock);
        TalkToClient tb = new TalkToClient(b, lock);

        // a takes the lock first (simulating an ongoing client dialogue)
        assertNull(lock.tryAcquire("CEO", "Lin Zong"));
        // b calls → lock held → error returned, never blocks on stdin
        String r = tb.handler(Map.of("message", "hello"));
        assertTrue(r.contains("Error"));
        assertTrue(r.contains("currently talking to the client"));
        assertTrue(r.contains("Lin Zong"));
        // after release, calling again no longer reports a lock conflict (it would then block on stdin; here we only verify the lock is released)
        lock.release("CEO");
        assertFalse(lock.isHeld());
    }
}
