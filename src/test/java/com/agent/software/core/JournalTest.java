package com.agent.software.core;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 角色活动日志 (journal) 测试 (Python 版 test_journal.py 的 Java 对应物).
 */
class JournalTest {

    @TempDir
    Path tmp;

    @Test
    void testAgentRoleJournalWritesFile() throws IOException {
        AgentRole.JOURNAL_DIR = tmp;
        AgentRole role = AgentRole.builder().name("测试").roleId("test_journal_role").build();
        role.journal("hello journal");
        String content = Files.readString(tmp.resolve("test_journal_role.md"));
        assertTrue(content.contains("hello journal"));
        assertTrue(content.startsWith("[D1 T0 "));
    }

    @Test
    void testAddTaskWritesJournal() throws IOException {
        AgentRole.JOURNAL_DIR = tmp;
        AgentRole role = AgentRole.builder().name("测试").roleId("test_journal_role").build();
        role.addTask(new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "写一篇技术文档", "", new java.util.LinkedHashMap<>()));
        String content = Files.readString(tmp.resolve("test_journal_role.md"));
        assertTrue(content.contains("收到任务"));
        assertTrue(content.contains("写一篇技术文档"));
        assertTrue(content.contains("NORMAL"));
    }

    @Test
    void testJournalAllWritesEveryRole() throws IOException {
        AgentRole.JOURNAL_DIR = tmp;
        RolePool pool = new RolePool();
        pool.addRole(AgentRole.builder().name("甲").roleId("role_a").build());
        pool.addRole(AgentRole.builder().name("乙").roleId("role_b").build());
        pool.journalAll("全局通知: 测试广播");
        for (String rid : new String[]{"role_a", "role_b"}) {
            String content = Files.readString(tmp.resolve(rid + ".md"));
            assertTrue(content.contains("全局通知: 测试广播"));
        }
    }

    @Test
    void testAddRoleCreatesJournalImmediately() throws IOException {
        AgentRole.JOURNAL_DIR = tmp;
        RolePool pool = new RolePool();
        pool.addRole(AgentRole.builder().name("张三").roleId("dev_1").title("前端开发").build());
        pool.addRole(AgentRole.builder().name("李四").roleId("dev_2").title("后端开发").build());
        String c1 = Files.readString(tmp.resolve("dev_1.md"));
        String c2 = Files.readString(tmp.resolve("dev_2.md"));
        assertTrue(c1.contains("角色就位") && c1.contains("张三"));
        assertTrue(c2.contains("角色就位") && c2.contains("李四"));
    }
}
