package com.agent.software;

import com.agent.software.computers.Computer;
import com.agent.software.computers.ComputerManager;
import com.agent.software.event.TimeEventBus;
import com.agent.software.role.AgentRole;
import com.agent.software.services.MailService;
import com.agent.software.tools.toolkits.client.ClientCommunicationLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multi-AgentSystem instance isolation tests (verification of the docs/agent-system-multi-instance.md refactor):
 *
 * Before the refactor, process-level global singletons (ComputerManager / MailService / MCPManager / SkillManager /
 * ClientCommunicationLock / default clock) and global data paths meant that only one
 * AgentSystem could run safely per process; after the refactor each AgentSystem holds its own
 * collaboration objects (clock / computer registry / mailbox / tool managers / dialogue lock / chat store)
 * and its own data directory, so a system can run independently and multiple systems do not interfere.
 */
class AgentSystemIsolationTest {

    @TempDir
    Path tmp;

    /** Creates an AgentSystem whose data dir is dir (same role set; autoToolkits=false avoids podman/LLM). */
    private AgentSystem make(Path dir) {
        return new AgentSystem(dir, null, List.of("CEO", "CTO"), 30.0, false);
    }

    // ── 1. The two systems' collaboration objects and roles are fully independent ─

    @Test
    void twoSystemsUseIndependentServicesAndRoles() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));

        // each system holds its own collaboration objects
        assertNotSame(a.timeManager, b.timeManager);
        assertNotSame(a.pool, b.pool);
        assertNotSame(a.dispatcher, b.dispatcher);
        assertNotSame(a.configStore, b.configStore);
        assertNotSame(a.computerManager, b.computerManager);
        assertNotSame(a.mailService, b.mailService);
        assertNotSame(a.mcpManager, b.mcpManager);
        assertNotSame(a.skillManager, b.skillManager);
        assertNotSame(a.clientLock, b.clientLock);
        assertNotSame(a.chatStore, b.chatStore);

        // roles with the same role_id are independent objects within their own systems
        assertNotSame(a.getRole("CEO"), b.getRole("CEO"));
        assertNotSame(a.getRole("CTO"), b.getRole("CTO"));

        // roles are bound to their owning system: clock/computer/mailbox/lock/chat all resolve to this system's
        // instances, not process-level default singletons (global fallbacks such as the default clock are no longer triggered by in-system roles)
        AgentRole ra = a.getRole("CEO");
        AgentRole rb = b.getRole("CEO");
        assertSame(a, ra.system());
        assertSame(b, rb.system());
        assertSame(a.timeManager, ra.timeManager());
        assertSame(b.timeManager, rb.timeManager());
        assertSame(a.computerManager, ra.computerManager());
        assertSame(b.computerManager, rb.computerManager());
        assertSame(a.mailService, ra.mailService());
        assertSame(b.mailService, rb.mailService());
        assertSame(a.clientLock, ra.clientLock());
        assertSame(b.clientLock, rb.clientLock());
        assertSame(a.chatStore, ra.chatStore());
        assertSame(b.chatStore, rb.chatStore());
    }

    // ── 2. Independent clocks: restoring progress only affects this system ─

    @Test
    void clocksAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));

        // a sets restore progress (Day 3 Tick 10) — b is completely unaffected
        a.timeManager.setProgress(3, 10);
        assertEquals(0, a.timeManager.currentTick());
        assertEquals(0, b.timeManager.currentTick());
        // roles read their own systems' clocks
        assertEquals(1, a.getRole("CEO").timeManager().dayNumber());
        assertEquals(1, b.getRole("CEO").timeManager().dayNumber());
    }

    // ── 3. Independent computer registries: same role_id registered separately without clobbering ─

    @Test
    void computerRegistriesAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        ComputerManager ma = a.computerManager;
        ComputerManager mb = b.computerManager;

        // the same role_id registers an independent local computer in each system
        Map<String, Object> kwA = new LinkedHashMap<>();
        kwA.put("base_dir", tmp.resolve("a").resolve("computers").toString());
        kwA.put("drive_dir", tmp.resolve("a").resolve("drive").toString());
        Map<String, Object> kwB = new LinkedHashMap<>();
        kwB.put("base_dir", tmp.resolve("b").resolve("computers").toString());
        kwB.put("drive_dir", tmp.resolve("b").resolve("drive").toString());
        Computer ca = ma.createComputer("local", "CEO", false, kwA);
        Computer cb = mb.createComputer("local", "CEO", false, kwB);
        ma.register(ca, "Lin Zong");
        mb.register(cb, "Lin Zong");

        assertSame(ca, ma.get("CEO"));
        assertSame(cb, mb.get("CEO"));
        // each system only sees its own registry
        assertThrows(IllegalArgumentException.class, () -> mb.get("CTO"));
        assertThrows(IllegalArgumentException.class, () -> ma.get("CTO"));
        assertEquals(1, ma.listAll().size());
        assertEquals(1, mb.listAll().size());
    }

    // ── 4. Independent mailboxes: system A's mail never enters system B's inbox ──

    @Test
    void mailboxesAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        MailService ma = a.mailService;
        MailService mb = b.mailService;

        ma.send("ceo@company.com", "Lin Zong", List.of("cto@company.com"), "Hello", "body-A", null);
        assertEquals(1, ma.inbox("cto@company.com", null).size());
        assertEquals(0, mb.inbox("cto@company.com", null).size());
        // each system's mailbox lives under its own data dir
        assertTrue(Files.exists(tmp.resolve("a").resolve("mail").resolve("mailboxes.json")));
        assertFalse(Files.exists(tmp.resolve("b").resolve("mail").resolve("mailboxes.json")));
    }

    // ── 5. Independent client dialogue locks: system A's hold does not affect system B ─

    @Test
    void clientLocksAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        ClientCommunicationLock la = a.clientLock;
        ClientCommunicationLock lb = b.clientLock;

        assertNull(la.tryAcquire("CEO", "Lin Zong"));
        // system B members are not blocked by system A's lock (before the refactor, the shared global singleton would block)
        assertNull(lb.tryAcquire("CTO", "Chen Zong"));
        assertTrue(la.isHeld());
        assertTrue(lb.isHeld());
        la.release("CEO");
        lb.release("CTO");
        assertFalse(la.isHeld());
        assertFalse(lb.isHeld());
    }

    // ── 6. Independent chat stores: system A's messages never enter system B ──

    @Test
    void chatStoresAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        a.chatStore.record("talk", "g", "CEO", "Lin Zong", "CTO", "Gao Yuan", "msg-A", "NORMAL");
        assertEquals(1, a.chatStore.messagesSince(0).size());
        assertEquals(0, b.chatStore.messagesSince(0).size());
    }

    // ── 7. Independent data files: notes/todos/journals isolated per system data dir ─

    @Test
    void notesTodosJournalsAreNamespaced() throws Exception {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        AgentRole ra = a.getRole("CEO");
        AgentRole rb = b.getRole("CEO");

        // notes
        ra.noteStore().writeNote("Requirements doc", "System A's requirements", null, null);
        assertTrue(Files.exists(tmp.resolve("a").resolve("notes").resolve("CEO")
                .resolve("notes").resolve("Requirements_doc.md")));
        assertTrue(rb.noteStore().listNotes().isEmpty());

        // todos
        ra.todoStore().add("System A's task", "detail");
        assertFalse(ra.todoStore().list(null).isEmpty());
        assertTrue(rb.todoStore().list(null).isEmpty());
        assertTrue(Files.exists(tmp.resolve("a").resolve("todos").resolve("CEO.json")));
        assertFalse(Files.exists(tmp.resolve("b").resolve("todos").resolve("CEO.json")));

        // journals: roles in each system write their own "Role ready", but a's entries only appear in a's file
        ra.journal("System A's journal entry");
        Path aLog = tmp.resolve("a").resolve("journals").resolve("CEO.md");
        Path bLog = tmp.resolve("b").resolve("journals").resolve("CEO.md");
        assertTrue(Files.exists(aLog));
        assertTrue(Files.exists(bLog));  // b's role-ready journal is written under its own dir
        assertTrue(Files.readString(aLog).contains("System A's journal entry"));
        assertFalse(Files.readString(bLog).contains("System A's journal entry"));
    }

    // ── 8. Default system keeps the legacy layout (./data/*) ─────

    @Test
    void defaultSystemUsesLegacyDataLayout() {
        AgentSystem def = new AgentSystem();
        assertEquals(Paths.get("data"), def.dataDir());
        assertEquals(Paths.get("data", "journals"), def.journalDir());
        assertEquals(Paths.get("data", "notes"), def.notesDir());
        assertEquals(Paths.get("data", "todos"), def.todosDir());
        assertEquals(Paths.get("data", "mail"), def.mailDir());
        assertEquals(Paths.get("data", "computers"), def.computersDir());
        assertEquals(Paths.get("data", "drive"), def.driveDir());
        assertEquals(Paths.get("data", "skills"), def.skillsDir());
        assertEquals(Paths.get("data", "state.json"), def.stateFile());
    }

    // ── 9. Standalone roles not bound to a system fall back to process defaults (legacy behavior) ─

    @Test
    void standaloneRoleFallsBackToProcessDefaults() {
        AgentRole standalone = AgentRole.builder().name("Newcomer").roleId("newbie_1").build();
        assertNull(standalone.system());
        assertNull(standalone.chatStore());
        assertSame(TimeEventBus.getDefaultBus(), standalone.timeManager());
        assertSame(ComputerManager.getInstance(), standalone.computerManager());
        assertSame(MailService.getMailService(), standalone.mailService());
        assertSame(ClientCommunicationLock.getInstance(), standalone.clientLock());
    }
}
