package com.maf.scheduler.core;

import com.maf.scheduler.core.Types.AgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色拼音用户名 + uid 分配测试 (Python 版 test_pinyin.py 的 Java 对应物).
 */
class PinyinTest {

    @TempDir
    Path tmp;

    @Test
    void testAllTemplatesHavePinyinUsername() {
        for (Map.Entry<String, java.util.function.Supplier<AgentRole>> e : RoleTemplates.TEMPLATES.entrySet()) {
            AgentRole r = e.getValue().get();
            assertTrue(r.username != null && !r.username.isEmpty(), e.getKey() + " 缺拼音 username");
            assertTrue(r.username.matches("[a-z0-9_]+"),
                    e.getKey() + " 的 username '" + r.username + "' 非法");
        }
    }

    @Test
    void testUnknownNameFallsBackToRoleId() {
        AgentRole r = AgentRole.builder().name("外星人").roleId("alien_1").build();
        assertEquals("alien_1", r.username);
    }

    @Test
    void testExplicitUsernameWins() {
        AgentRole r = AgentRole.builder().name("郭晓东").roleId("tester_1").username("gxd").build();
        assertEquals("gxd", r.username);
    }

    @Test
    void testUidAssignedByRegistrationOrder() {
        AgentRole.JOURNAL_DIR = tmp;
        RolePool pool = new RolePool();
        AgentRole a = AgentRole.builder().name("郭晓东").roleId("tester_1").build();
        AgentRole b = AgentRole.builder().name("王建国").roleId("architect").build();
        pool.addRole(a);
        pool.addRole(b);
        assertEquals(1101, a.uid);
        assertEquals(1102, b.uid);
        AgentRole c = AgentRole.builder().name("林总").roleId("CEO").uid(1200).build();
        pool.addRole(c);
        assertEquals(1200, c.uid);
    }

    @Test
    void testSystemPromptMentionsCloudDrive() {
        AgentRole.JOURNAL_DIR = tmp;
        String oldProp = System.getProperty("AGENTSCHEDULER_DATA_DIR");
        System.setProperty("AGENTSCHEDULER_DATA_DIR", tmp.resolve("data").toString());
        try {
            RolePool pool = new RolePool();
            AgentRole r = AgentRole.builder().name("郭晓东").roleId("tester_1").computerKind("local").build();
            pool.addRole(r);
            String prompt = r.buildSystemPrompt();
            assertTrue(prompt.contains("/mnt/drive"));
            assertTrue(prompt.contains("Public"));
            assertTrue(prompt.contains("/mnt/drive/郭晓东"));
            assertTrue(prompt.contains("只读") || prompt.contains("只有你能写入"));
            assertTrue(prompt.contains("Git"));
            assertTrue(prompt.contains("git pull"));
            assertTrue(prompt.contains("commit") && prompt.contains("push"));
            assertTrue(prompt.contains("合并"));
        } finally {
            if (oldProp == null) {
                System.clearProperty("AGENTSCHEDULER_DATA_DIR");
            } else {
                System.setProperty("AGENTSCHEDULER_DATA_DIR", oldProp);
            }
        }
    }

    @Test
    void testReleaseManagerPromptMentionsProjectDir() {
        AgentRole.JOURNAL_DIR = tmp;
        String oldProp = System.getProperty("AGENTSCHEDULER_DATA_DIR");
        System.setProperty("AGENTSCHEDULER_DATA_DIR", tmp.resolve("data").toString());
        try {
            RolePool pool = new RolePool();
            AgentRole r = RoleTemplates.getTemplate("release_manager");
            r.computerKind = "local";  // 避免 buildSystemPrompt 触发 podman 开机
            pool.addRole(r);
            String prompt = r.buildSystemPrompt();
            assertTrue(prompt.contains("/mnt/drive/Public/work/"));
            assertTrue(prompt.contains("git init"));
            assertTrue(prompt.contains("创建 git 仓库") || prompt.contains("创建 git 项目"));
        } finally {
            if (oldProp == null) {
                System.clearProperty("AGENTSCHEDULER_DATA_DIR");
            } else {
                System.setProperty("AGENTSCHEDULER_DATA_DIR", oldProp);
            }
        }
    }

    @Test
    void testEventFilterLayerBasics() {
        // 3 层过滤基础行为: 时间事件穿透; OFF_DUTY 拦截非紧急事件
        AgentRole role = AgentRole.builder().name("测试").roleId("tester").build();
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("tick", 0);
        Types.Event timeEvent = new Types.Event("time", "SHIFT_START", Types.Priority.EMERGENCY, payload, null);
        assertTrue(role.evaluateEvent(timeEvent).getKey());

        role.setState(AgentState.OFF_DUTY);
        Types.Event lowEvent = new Types.Event("slack", "chat", Types.Priority.LOW,
                Map.of("text", "hi"), null);
        assertEquals(false, role.evaluateEvent(lowEvent).getKey());
        // 紧急事件穿透 OFF_DUTY
        Types.Event critical = new Types.Event("ops", "incident", Types.Priority.EMERGENCY,
                Map.of("text", "outage"), null);
        assertTrue(role.evaluateEvent(critical).getKey());
    }
}
