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
 * 多 AgentSystem 实例隔离测试 (docs/agent-system-multi-instance.md 改造验证):
 *
 * 改造前进程级全局单例 (ComputerManager / MailService / MCPManager / SkillManager /
 * ClientCommunicationLock / 默认时钟) 与全局数据路径使同一进程内只能安全运行一个
 * AgentSystem; 改造后每套 AgentSystem 通过 {@link AgentSystemContext} 持有独立的
 * 协作对象与数据目录. 本测试在同一 JVM 内创建两套同角色集系统, 验证互不干扰.
 */
class AgentSystemIsolationTest {

    @TempDir
    Path tmp;

    /** 创建一套数据目录为 dir 的 AgentSystem (同角色集, autoToolkits=false 避免 podman/LLM). */
    private AgentSystem make(Path dir) {
        return new AgentSystem(AgentSystemContext.create(dir),
                null, List.of("CEO", "CTO"), 30.0, false);
    }

    // ── 1. 两套系统的协作对象与角色完全独立 ─────────────────

    @Test
    void twoSystemsUseIndependentServicesAndRoles() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));

        // 每系统独立的协作对象
        assertNotSame(a.timeManager, b.timeManager);
        assertNotSame(a.pool, b.pool);
        assertNotSame(a.dispatcher, b.dispatcher);
        assertNotSame(a.configStore, b.configStore);
        assertNotSame(a.context().computerManager, b.context().computerManager);
        assertNotSame(a.context().mailService, b.context().mailService);
        assertNotSame(a.context().mcpManager, b.context().mcpManager);
        assertNotSame(a.context().skillManager, b.context().skillManager);
        assertNotSame(a.context().clientLock, b.context().clientLock);

        // 同 role_id 的角色是各自系统内的独立对象
        assertNotSame(a.getRole("CEO"), b.getRole("CEO"));
        assertNotSame(a.getRole("CTO"), b.getRole("CTO"));

        // 角色已绑定本系统上下文: 时钟/电脑/邮箱/锁全部解析到本系统实例,
        // 而不是进程级默认单例 (默认时钟等全局回退不再被系统内角色触发)
        AgentRole ra = a.getRole("CEO");
        AgentRole rb = b.getRole("CEO");
        assertSame(a.context(), ra.context());
        assertSame(b.context(), rb.context());
        assertSame(a.timeManager, ra.timeManager());
        assertSame(b.timeManager, rb.timeManager());
        assertSame(a.context().computerManager, ra.computerManager());
        assertSame(b.context().computerManager, rb.computerManager());
        assertSame(a.context().mailService, ra.mailService());
        assertSame(b.context().mailService, rb.mailService());
        assertSame(a.context().clientLock, ra.clientLock());
        assertSame(b.context().clientLock, rb.clientLock());
    }

    // ── 2. 时钟独立: 恢复进度只影响本系统 ────────────────────

    @Test
    void clocksAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));

        // a 设置恢复进度 (第 3 天 Tick 10) — b 完全不受影响
        a.timeManager.setProgress(3, 10);
        assertEquals(0, a.timeManager.currentTick());
        assertEquals(0, b.timeManager.currentTick());
        // 角色读取的是各自系统的时钟
        assertEquals(1, a.getRole("CEO").timeManager().dayNumber());
        assertEquals(1, b.getRole("CEO").timeManager().dayNumber());
    }

    // ── 3. 电脑注册表独立: 同 role_id 各自注册不互相覆盖 ─────

    @Test
    void computerRegistriesAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        ComputerManager ma = a.context().computerManager;
        ComputerManager mb = b.context().computerManager;

        // 同一 role_id 在两套系统分别注册独立的 local 电脑
        Map<String, Object> kwA = new LinkedHashMap<>();
        kwA.put("base_dir", tmp.resolve("a").resolve("computers").toString());
        kwA.put("drive_dir", tmp.resolve("a").resolve("drive").toString());
        Map<String, Object> kwB = new LinkedHashMap<>();
        kwB.put("base_dir", tmp.resolve("b").resolve("computers").toString());
        kwB.put("drive_dir", tmp.resolve("b").resolve("drive").toString());
        Computer ca = ma.createComputer("local", "CEO", false, kwA);
        Computer cb = mb.createComputer("local", "CEO", false, kwB);
        ma.register(ca, "林总");
        mb.register(cb, "林总");

        assertSame(ca, ma.get("CEO"));
        assertSame(cb, mb.get("CEO"));
        // 各自系统只看到自己的注册表
        assertThrows(IllegalArgumentException.class, () -> mb.get("CTO"));
        assertThrows(IllegalArgumentException.class, () -> ma.get("CTO"));
        assertEquals(1, ma.listAll().size());
        assertEquals(1, mb.listAll().size());
    }

    // ── 4. 邮箱独立: 系统 A 发信不进入系统 B 的收件箱 ────────

    @Test
    void mailboxesAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        MailService ma = a.context().mailService;
        MailService mb = b.context().mailService;

        ma.send("ceo@company.com", "林总", List.of("cto@company.com"), "Hello", "body-A", null);
        assertEquals(1, ma.inbox("cto@company.com", null).size());
        assertEquals(0, mb.inbox("cto@company.com", null).size());
        // 各系统邮箱落在各自数据目录
        assertTrue(Files.exists(tmp.resolve("a").resolve("mail").resolve("mailboxes.json")));
        assertFalse(Files.exists(tmp.resolve("b").resolve("mail").resolve("mailboxes.json")));
    }

    // ── 5. 与甲方对话锁独立: 系统 A 占用不影响系统 B ─────────

    @Test
    void clientLocksAreIndependent() {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        ClientCommunicationLock la = a.context().clientLock;
        ClientCommunicationLock lb = b.context().clientLock;

        assertNull(la.tryAcquire("CEO", "林总"));
        // 系统 B 的成员不受系统 A 的锁影响 (改造前共享全局单例会被阻塞)
        assertNull(lb.tryAcquire("CTO", "陈总"));
        assertTrue(la.isHeld());
        assertTrue(lb.isHeld());
        la.release("CEO");
        lb.release("CTO");
        assertFalse(la.isHeld());
        assertFalse(lb.isHeld());
    }

    // ── 6. 数据文件独立: 笔记/待办/活动日志按系统数据目录隔离 ──

    @Test
    void notesTodosJournalsAreNamespaced() throws Exception {
        AgentSystem a = make(tmp.resolve("a"));
        AgentSystem b = make(tmp.resolve("b"));
        AgentRole ra = a.getRole("CEO");
        AgentRole rb = b.getRole("CEO");

        // 笔记
        ra.noteStore().writeNote("需求文档", "系统 A 的需求", null, null);
        assertTrue(Files.exists(tmp.resolve("a").resolve("notes").resolve("CEO")
                .resolve("notes").resolve("需求文档.md")));
        assertTrue(rb.noteStore().listNotes().isEmpty());

        // 待办
        ra.todoStore().add("系统 A 的任务", "detail");
        assertFalse(ra.todoStore().list(null).isEmpty());
        assertTrue(rb.todoStore().list(null).isEmpty());
        assertTrue(Files.exists(tmp.resolve("a").resolve("todos").resolve("CEO.json")));
        assertFalse(Files.exists(tmp.resolve("b").resolve("todos").resolve("CEO.json")));

        // 活动日志: 各系统角色都写自己的"角色就位", 但 a 的日志条目只出现在 a 的文件里
        ra.journal("系统 A 的日志条目");
        Path aLog = tmp.resolve("a").resolve("journals").resolve("CEO.md");
        Path bLog = tmp.resolve("b").resolve("journals").resolve("CEO.md");
        assertTrue(Files.exists(aLog));
        assertTrue(Files.exists(bLog));  // b 的角色就位日志写在自己的目录
        assertTrue(Files.readString(aLog).contains("系统 A 的日志条目"));
        assertFalse(Files.readString(bLog).contains("系统 A 的日志条目"));
    }

    // ── 7. 默认上下文保持历史布局 (./data/*) ─────────────────

    @Test
    void defaultContextUsesLegacyDataLayout() {
        AgentSystemContext def = AgentSystemContext.createDefault();
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

    // ── 8. 未绑定上下文的独立角色回退进程级默认 (旧行为保留) ──

    @Test
    void standaloneRoleFallsBackToProcessDefaults() {
        AgentRole standalone = AgentRole.builder().name("新人").roleId("newbie_1").build();
        assertNull(standalone.context());
        assertSame(TimeEventBus.getDefaultBus(), standalone.timeManager());
        assertSame(ComputerManager.getInstance(), standalone.computerManager());
        assertSame(MailService.getMailService(), standalone.mailService());
        assertSame(ClientCommunicationLock.getInstance(), standalone.clientLock());
    }
}
