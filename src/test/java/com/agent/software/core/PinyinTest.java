package com.agent.software.core;

import com.agent.software.core.Types.AgentState;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.role.RolePool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Role pinyin username + uid allocation tests (the Java counterpart of the Python test_pinyin.py).
 */
class PinyinTest {

    @TempDir
    Path tmp;

    @Test
    void testAllTemplatesHavePinyinUsername() {
        for (Map.Entry<String, java.util.function.Supplier<AgentRole>> e : RoleLoader.TEMPLATES.entrySet()) {
            AgentRole r = e.getValue().get();
            assertTrue(r.username != null && !r.username.isEmpty(), e.getKey() + " missing pinyin username");
            assertTrue(r.username.matches("[a-z0-9_]+"),
                    e.getKey() + " has invalid username '" + r.username + "'");
        }
    }

    @Test
    void testUnknownNameFallsBackToRoleId() {
        AgentRole r = AgentRole.builder().name("Alien").roleId("alien_1").build();
        assertEquals("alien_1", r.username);
    }

    @Test
    void testExplicitUsernameWins() {
        AgentRole r = AgentRole.builder().name("Guo Xiaodong").roleId("tester_1").username("gxd").build();
        assertEquals("gxd", r.username);
    }

    @Test
    void testUidAssignedByRegistrationOrder() {
        AgentRole.JOURNAL_DIR = tmp;
        RolePool pool = new RolePool();
        AgentRole a = AgentRole.builder().name("Guo Xiaodong").roleId("tester_1").build();
        AgentRole b = AgentRole.builder().name("Wang Jianguo").roleId("architect").build();
        pool.addRole(a);
        pool.addRole(b);
        assertEquals(1101, a.uid);
        assertEquals(1102, b.uid);
        AgentRole c = AgentRole.builder().name("Lin Zong").roleId("CEO").uid(1200).build();
        pool.addRole(c);
        assertEquals(1200, c.uid);
    }

    @Test
    void testSystemPromptMentionsCloudDrive() {
        AgentRole.JOURNAL_DIR = tmp;
        String oldProp = System.getProperty("AGENTCOMPANY_DATA_DIR");
        System.setProperty("AGENTCOMPANY_DATA_DIR", tmp.resolve("data").toString());
        try {
            RolePool pool = new RolePool();
            AgentRole r = AgentRole.builder().name("Guo Xiaodong").roleId("tester_1").computerKind("local").build();
            pool.addRole(r);
            String prompt = r.buildSystemPrompt();
            assertTrue(prompt.contains("/mnt/drive"));
            assertTrue(prompt.contains("Public"));
            assertTrue(prompt.contains("/mnt/drive/tester_1"));
            assertTrue(prompt.contains("read-only") || prompt.contains("only you can write"));
            assertTrue(prompt.contains("Git"));
            assertTrue(prompt.contains("git pull"));
            assertTrue(prompt.contains("commit") && prompt.contains("push"));
            assertTrue(prompt.contains("merge"));
        } finally {
            if (oldProp == null) {
                System.clearProperty("AGENTCOMPANY_DATA_DIR");
            } else {
                System.setProperty("AGENTCOMPANY_DATA_DIR", oldProp);
            }
        }
    }

    @Test
    void testReleaseManagerPromptMentionsProjectDir() {
        AgentRole.JOURNAL_DIR = tmp;
        String oldProp = System.getProperty("AGENTCOMPANY_DATA_DIR");
        System.setProperty("AGENTCOMPANY_DATA_DIR", tmp.resolve("data").toString());
        try {
            RolePool pool = new RolePool();
            AgentRole r = RoleLoader.getTemplate("release_manager");
            r.computerKind = "local";  // avoid buildSystemPrompt triggering podman power-on
            pool.addRole(r);
            String prompt = r.buildSystemPrompt();
            assertTrue(prompt.contains("/mnt/drive/Public/work/"));
            assertTrue(prompt.contains("git init"));
            assertTrue(prompt.contains("git repository"));
        } finally {
            if (oldProp == null) {
                System.clearProperty("AGENTCOMPANY_DATA_DIR");
            } else {
                System.setProperty("AGENTCOMPANY_DATA_DIR", oldProp);
            }
        }
    }

    @Test
    void testEventFilterLayerBasics() {
        // basic 3-layer filter behavior: time events pass through; OFF_DUTY blocks non-urgent events
        AgentRole role = AgentRole.builder().name("Test").roleId("tester").build();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("tick", 0);
        Types.Event timeEvent = new Types.Event("time", "SHIFT_START", Types.Priority.EMERGENCY, payload, null);
        assertTrue(role.evaluateEvent(timeEvent).getKey());

        role.setState(AgentState.OFF_DUTY);
        Types.Event lowEvent = new Types.Event("slack", "chat", Types.Priority.LOW,
                Map.of("text", "hi"), null);
        assertEquals(false, role.evaluateEvent(lowEvent).getKey());
        // urgent events pass through OFF_DUTY
        Types.Event critical = new Types.Event("ops", "incident", Types.Priority.EMERGENCY,
                Map.of("text", "outage"), null);
        assertTrue(role.evaluateEvent(critical).getKey());
    }
}
