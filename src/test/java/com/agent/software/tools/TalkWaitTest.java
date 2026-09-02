package com.agent.software.tools;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import com.agent.software.tools.toolkits.talk.ListRoles;
import com.agent.software.tools.toolkits.talk.Talk;
import com.agent.software.core.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * talk tool wait=true synchronous reply-waiting tests (the Java counterpart of the Python test_talk_wait.py).
 */
class TalkWaitTest {

    @TempDir
    Path tmp;

    private static boolean waitUntil(java.util.function.Supplier<Boolean> pred, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(pred.get())) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    private static Map<String, Talk> setupRoles(RolePool pool, String... roleIds) {
        Map<String, Talk> toolkits = new LinkedHashMap<>();
        for (String rid : roleIds) {
            AgentRole role = AgentRole.builder().name("Role " + rid).roleId(rid).build();
            pool.addRole(role);
            role.setPool(pool);  // simulate the back-reference after start()
            toolkits.put(rid, new Talk(role, pool));
        }
        return toolkits;
    }

    /** Calls the sender's talk tool (same logic as call_tool). */
    private static String talk(Map<String, Talk> toolkits, String senderId,
                               String target, String message, boolean wait) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("target", target);
        args.put("message", message);
        if (wait) {
            args.put("wait", true);
        }
        return toolkits.get(senderId).trigger("talk", args);
    }

    // ── Person-name exposure ──────────────────────────────────

    @Test
    void testRosterHidesRoleId() {
        RolePool pool = new RolePool();
        pool.addRole(AgentRole.builder().name("Zhang San").roleId("dev_1").build());
        pool.addRole(AgentRole.builder().name("Li Si").roleId("dev_2").build());
        String roster = ListRoles.buildTeamRoster(pool);
        assertTrue(roster.contains("Zhang San") && roster.contains("Li Si"));
        assertFalse(roster.contains("dev_1"));
        assertFalse(roster.contains("dev_2"));
        assertFalse(roster.contains("role_id"));
    }

    @Test
    void testTalkByPersonName() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool, "A", "B");
        AgentRole roleB = pool.getRole("B");
        String result = talk(tks, "A", "Role B", "send-by-name test", false);
        assertTrue(result.contains("message sent to Role B"));
        assertEquals(1, roleB.queueDepth());
    }

    @Test
    void testTalkUnknownNameGivesHint() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool, "A", "B");
        String result = talk(tks, "A", "Nonexistent name", "hi", false);
        assertTrue(result.contains("cannot find"));
        assertTrue(result.contains("list_roles"));
    }

    // ── 1) wait round trip ────────────────────────────────────

    @Test
    void testWaitRoundtrip() throws InterruptedException {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool, "A", "B");
        AgentRole roleA = pool.getRole("A");
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talk(tks, "A", "B", "What's the progress?", true)));
        t.start();
        try {
            assertTrue(waitUntil(() -> roleA.state == Types.AgentState.WAIT, 5000), "A did not enter WAIT");
            String reply = talk(tks, "B", "A", "80% done", false);
            assertTrue(reply.contains("replied to Role A who was waiting"));
            t.join(5000);
        } finally {
            t.join(1000);
            pool.shutdown(false);
        }
        assertFalse(t.isAlive());
        assertTrue(result.get().contains("received reply from Role B: 80% done"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, roleA.state);  // state restored
    }

    @Test
    void testWaitRoundtripWithRealNames() throws InterruptedException {
        RolePool pool = new RolePool();
        AgentRole a = AgentRole.builder().name("Wang Jianguo").roleId("architect").build();
        AgentRole b = AgentRole.builder().name("Guo Xiaodong").roleId("tester_1").build();
        pool.addRole(a);
        pool.addRole(b);
        a.setPool(pool);
        b.setPool(pool);
        Map<String, Talk> tks = new LinkedHashMap<>();
        for (AgentRole r : new AgentRole[]{a, b}) {
            tks.put(r.roleId, new Talk(r, pool));
        }
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talk(tks, "architect", "Guo Xiaodong", "Progress?", true)));
        t.start();
        try {
            assertTrue(waitUntil(() -> a.state == Types.AgentState.WAIT, 5000), "A did not enter WAIT");
            assertEquals("tester_1", a.waitingReplyFrom());  // the internal wait chain stores the role_id
            String reply = talk(tks, "tester_1", "Wang Jianguo", "80% done", false);
            assertTrue(reply.contains("replied to Wang Jianguo who was waiting"));
            t.join(5000);
        } finally {
            t.join(1000);
            pool.shutdown(false);
        }
        assertFalse(t.isAlive());
        assertTrue(result.get().contains("received reply from Guo Xiaodong: 80% done"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, a.state);
    }

    // ── 2) Mutual wait decomposed ─────────────────────────────

    @Test
    void testMutualWaitDecomposed() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool, "A", "B");
        AgentRole roleA = pool.getRole("A");
        AgentRole roleB = pool.getRole("B");
        roleA.beginWait("B");  // A is waiting for B's reply
        try {
            String result = talk(tks, "B", "A", "Got it, handling it right away", true);
            assertTrue(result.contains("replied to Role A who was waiting"));
            assertEquals("Got it, handling it right away", roleA.debugReplyBox());  // delivered into A's mailbox
            assertEquals(Types.AgentState.ON_DUTY_IDLE, roleB.state);  // B did not enter WAIT
        } finally {
            roleA.endWait();
        }
    }

    // ── 3) Circular wait rejected ─────────────────────────────

    @Test
    void testDeadlockCycleRejected() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool, "A", "B", "C");
        AgentRole roleA = pool.getRole("A");
        AgentRole roleB = pool.getRole("B");
        AgentRole roleC = pool.getRole("C");
        roleB.beginWait("C");
        roleC.beginWait("A");
        try {
            String result = talk(tks, "A", "B", "Urgent matter", true);
            assertTrue(result.contains("deadlock"));
            assertEquals(Types.AgentState.ON_DUTY_IDLE, roleA.state);  // A did not enter WAIT
        } finally {
            roleB.endWait();
            roleC.endWait();
        }
    }

    // ── 4) Infinite wait + waiting hint ───────────────────────

    @Test
    void testWaitMessageCarriesWaitingHint() throws InterruptedException {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool, "A", "B");
        AgentRole roleA = pool.getRole("A");
        AgentRole roleB = pool.getRole("B");
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talk(tks, "A", "B", "How's the progress?", true)));
        t.start();
        try {
            assertTrue(waitUntil(() -> roleB.queueDepth() == 1, 5000), "B did not receive the message");
            AgentRole.Task task = roleB.popTask();
            assertTrue(task != null && task.description.contains("is waiting for your reply"));
            assertTrue(task.description.contains("Role A"));
            assertEquals(Boolean.TRUE, task.context.get("waiting"));
            // no timeout: still in WAIT after 1.5s
            Thread.sleep(1500);
            assertEquals(Types.AgentState.WAIT, roleA.state);
            String reply = talk(tks, "B", "A", "80% done", false);
            assertTrue(reply.contains("replied to Role A who was waiting"));
            t.join(5000);
        } finally {
            t.join(1000);
            pool.shutdown(false);
        }
        assertFalse(t.isAlive());
        assertTrue(result.get().contains("received reply from Role B: 80% done"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, roleA.state);
    }

    // ── 5) Messages to non-waiting targets are not delivered as replies ─

    @Test
    void testThirdPartyMessageNotDeliveredAsReply() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool, "A", "B", "C");
        AgentRole roleA = pool.getRole("A");
        roleA.beginWait("B");
        try {
            String result = talk(tks, "C", "A", "Regular message", false);
            assertTrue(result.contains("message sent to"));
            assertEquals(Types.AgentState.WAIT, roleA.state);  // still waiting for B
            assertNull(roleA.debugReplyBox());
            assertEquals(1, roleA.queueDepth());  // message queued, processed after recovery
        } finally {
            roleA.endWait();
        }
    }

    // ── 6) Attachments (company cloud drive /mnt/drive files) ──

    @Test
    void testTalkAttachmentValidatedAndCarried() {
        AgentRole.JOURNAL_DIR = tmp.resolve("journals");
        RolePool pool = new RolePool();
        Map<String, Object> kw = new LinkedHashMap<>();
        kw.put("drive_dir", tmp.resolve("drive").toString());
        AgentRole a = AgentRole.builder().name("Guo Xiaodong").roleId("tester_1")
                .computerKind("local").computerKwargs(kw).build();
        AgentRole b = AgentRole.builder().name("Wang Jianguo").roleId("architect")
                .computerKind("local").computerKwargs(kw).build();
        pool.addRole(a);
        pool.addRole(b);
        // Guo Xiaodong places an attachment on the cloud drive
        a.computer().writeFile(a.computer().driveRoot() + "/drafts/design-doc.md", "attachment content");

        Talk talkA = new Talk(a, pool);
        Talk talkB = new Talk(b, pool);

        // invalid attachment (missing) → rejected
        Map<String, Object> badArgs = new LinkedHashMap<>();
        badArgs.put("target", "Wang Jianguo");
        badArgs.put("message", "Take a look");
        badArgs.put("attachment", "drafts/nonexistent.md");
        String r = talkA.trigger("talk", badArgs);
        assertTrue(r.contains("invalid attachment"));
        assertEquals(0, b.queueDepth());

        // valid attachment → delivered, task description carries the attachment hint
        Map<String, Object> okArgs = new LinkedHashMap<>();
        okArgs.put("target", "Wang Jianguo");
        okArgs.put("message", "Take a look at the design doc");
        okArgs.put("attachment", "drafts/design-doc.md");
        String r2 = talkA.trigger("talk", okArgs);
        assertTrue(r2.contains("message sent to Wang Jianguo"));
        AgentRole.Task task = b.popTask();
        assertTrue(task != null && task.description.contains("[Attachment: drafts/design-doc.md]"));
        assertTrue(task.description.contains("mnt/drive"));
        assertEquals("drafts/design-doc.md", task.context.get("attachment"));
        pool.shutdown(false);
    }
}
