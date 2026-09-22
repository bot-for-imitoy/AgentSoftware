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
 * Role activity journal (journal) tests (the Java counterpart of the Python test_journal.py).
 */
class JournalTest {

    @TempDir
    Path tmp;

    @Test
    void testAgentRoleJournalWritesFile() throws IOException {
        AgentRole.JOURNAL_DIR = tmp;
        AgentRole role = AgentRole.builder().name("Test").roleId("test_journal_role").build();
        role.journal("hello journal");
        String content = Files.readString(tmp.resolve("test_journal_role.md"));
        assertTrue(content.contains("hello journal"));
        assertTrue(content.startsWith("[D1 T0 "));
    }

    @Test
    void testAddTaskWritesJournal() throws IOException {
        AgentRole.JOURNAL_DIR = tmp;
        AgentRole role = AgentRole.builder().name("Test").roleId("test_journal_role").build();
        role.addTask(new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "Write a technical document", "", new java.util.LinkedHashMap<>()));
        String content = Files.readString(tmp.resolve("test_journal_role.md"));
        assertTrue(content.contains("Task received"));
        assertTrue(content.contains("Write a technical document"));
        assertTrue(content.contains("NORMAL"));
    }

    @Test
    void testJournalAllWritesEveryRole() throws IOException {
        AgentRole.JOURNAL_DIR = tmp;
        RolePool pool = new RolePool();
        pool.addRole(AgentRole.builder().name("A").roleId("role_a").build());
        pool.addRole(AgentRole.builder().name("B").roleId("role_b").build());
        pool.journalAll("Broadcast: test announcement");
        for (String rid : new String[]{"role_a", "role_b"}) {
            String content = Files.readString(tmp.resolve(rid + ".md"));
            assertTrue(content.contains("Broadcast: test announcement"));
        }
    }

    @Test
    void testAddRoleCreatesJournalImmediately() throws IOException {
        AgentRole.JOURNAL_DIR = tmp;
        RolePool pool = new RolePool();
        pool.addRole(AgentRole.builder().name("Zhang San").roleId("dev_1").title("Frontend Developer").build());
        pool.addRole(AgentRole.builder().name("Li Si").roleId("dev_2").title("Backend Developer").build());
        String c1 = Files.readString(tmp.resolve("dev_1.md"));
        String c2 = Files.readString(tmp.resolve("dev_2.md"));
        assertTrue(c1.contains("Role ready") && c1.contains("Zhang San"));
        assertTrue(c2.contains("Role ready") && c2.contains("Li Si"));
    }
}
