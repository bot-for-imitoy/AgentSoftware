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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * talk tool in-group communication restriction tests (the Java counterpart of the Python test_talk_group.py).
 */
class TalkGroupTest {

    @TempDir
    Path tmp;

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

    private static AgentRole role(String name, String rid, String group) {
        return AgentRole.builder().name(name).roleId(rid).group(group).build();
    }

    private static Map<String, Talk> setupRoles(RolePool pool, AgentRole... roles) {
        Map<String, Talk> toolkits = new LinkedHashMap<>();
        for (AgentRole role : roles) {
            pool.addRole(role);
            role.setPool(pool);
            toolkits.put(role.roleId, new Talk(role, pool));
        }
        return toolkits;
    }

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

    // ── Same group allowed ────────────────────────────────────

    @Test
    void testSameGroupAllowed() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool,
                role("Gu Chengyu", "frontend_dev_1", "Frontend Development Group"),
                role("Chen Siyuan", "frontend_lead", "Frontend Development Group"));
        String result = talk(tks, "frontend_dev_1", "Chen Siyuan", "Component refactor done", false);
        assertTrue(result.contains("message sent to Chen Siyuan"));
        assertEquals(1, pool.getRole("frontend_lead").queueDepth());
    }

    @Test
    void testSameGroupWaitRoundtrip() throws InterruptedException {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool,
                role("Gu Chengyu", "frontend_dev_1", "Frontend Development Group"),
                role("Chen Siyuan", "frontend_lead", "Frontend Development Group"));
        AgentRole roleA = pool.getRole("frontend_dev_1");
        AtomicReference<String> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(talk(tks, "frontend_dev_1", "Chen Siyuan", "Progress?", true)));
        t.start();
        try {
            assertTrue(waitUntil(() -> roleA.state == Types.AgentState.WAIT, 5000), "A did not enter WAIT");
            String reply = talk(tks, "frontend_lead", "Gu Chengyu", "80% done", false);
            assertTrue(reply.contains("replied to Gu Chengyu who was waiting"));
            t.join(5000);
        } finally {
            t.join(1000);
            pool.shutdown(false);
        }
        assertFalse(t.isAlive());
        assertTrue(result.get().contains("received reply from Chen Siyuan: 80% done"));
    }

    // ── Cross-group rejected ──────────────────────────────────

    @Test
    void testCrossGroupRejected() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool,
                role("Guo Xiaodong", "tester_1", "Testing Group"),
                role("Wang Jianguo", "architect", "Architecture & Release Group"));
        String result = talk(tks, "tester_1", "Wang Jianguo", "Have an architecture question", false);
        assertTrue(result.contains("only for communication within the same group"));
        assertTrue(result.contains("Testing Group") && result.contains("Architecture & Release Group"));
        assertTrue(result.contains("send_email"));
        assertEquals(0, pool.getRole("architect").queueDepth());  // not delivered
    }

    @Test
    void testCrossGroupWaitRejected() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool,
                role("Guo Xiaodong", "tester_1", "Testing Group"),
                role("Fang Jinyan", "release_manager", "Architecture & Release Group"));
        AgentRole roleA = pool.getRole("tester_1");
        String result = talk(tks, "tester_1", "Fang Jinyan", "Urgent matter", true);
        assertTrue(result.contains("only for communication within the same group"));
        assertEquals(Types.AgentState.ON_DUTY_IDLE, roleA.state);  // did not enter WAIT
    }

    // ── Ungrouped roles unrestricted ──────────────────────────

    @Test
    void testUngroupedRolesUnrestricted() {
        RolePool pool = new RolePool();
        Map<String, Talk> tks = setupRoles(pool,
                role("Newcomer", "newbie_1", ""),
                role("Gu Chengyu", "frontend_dev_1", "Frontend Development Group"));
        String r1 = talk(tks, "newbie_1", "Gu Chengyu", "Hello", false);
        assertTrue(r1.contains("message sent to Gu Chengyu"));
        String r2 = talk(tks, "frontend_dev_1", "Newcomer", "Welcome", false);
        assertTrue(r2.contains("message sent to Newcomer"));
    }

    // ── Roster shows group ────────────────────────────────────

    @Test
    void testRosterShowsGroup() {
        RolePool pool = new RolePool();
        pool.addRole(role("Gu Chengyu", "frontend_dev_1", "Frontend Development Group"));
        pool.addRole(role("Lin Zong", "CEO", "Leadership Group"));
        pool.addRole(role("Newcomer", "newbie_1", ""));
        String roster = ListRoles.buildTeamRoster(pool);
        assertTrue(roster.contains("(Group: Frontend Development Group)"));
        assertTrue(roster.contains("(Group: Leadership Group)"));
        assertTrue(roster.contains("(Group: Unassigned)"));
        assertFalse(roster.contains("frontend_dev_1"));
    }
}
