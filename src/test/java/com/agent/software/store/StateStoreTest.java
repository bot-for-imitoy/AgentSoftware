package com.agent.software.store;

import com.agent.software.AgentSystem;
import com.agent.software.computers.Computer;
import com.agent.software.computers.ComputerManager;
import com.agent.software.core.Types;
import com.agent.software.role.AgentRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一状态存储 (StateStore) 测试 (Python 版 test_state_store.py 的 Java 对应物).
 */
class StateStoreTest {

    @TempDir
    Path tmp;

    private AgentSystem make(String rid) {
        AgentRole.JOURNAL_DIR = tmp.resolve("journals");
        return new AgentSystem(null, java.util.List.of(rid), 30.0, false);
    }

    @Test
    void testSaveRestoreRoundtrip() {
        StateStore store = new StateStore(tmp.resolve("state.json").toString());
        AgentSystem s1 = make("CEO");
        AgentRole ceo = s1.getRole("CEO");
        AgentRole.Task done = new AgentRole.Task(AgentRole.Urgency.HIGH.value, "完成登录页开发", "github",
                new LinkedHashMap<>());
        done.status = "done";
        done.result = "已交付";
        done.tokensConsumed = 456;
        ceo.appendTaskHistory(done);
        ceo.addTask(new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "待办: 写周报", "", new LinkedHashMap<>()));
        s1.timeManager.debugSetTick(30);  // 第 1 天 Tick 30
        store.save(s1);

        AgentSystem s2 = make("CEO");
        assertEquals(1, store.restore(s2));
        AgentRole ceo2 = s2.getRole("CEO");
        assertEquals("完成登录页开发", ceo2.taskHistory(null).get(0).description);
        assertEquals(456, ceo2.taskHistory(null).get(0).tokensConsumed);
        assertEquals(1, ceo2.queueDepth());
        assertEquals(AgentRole.Urgency.NORMAL, ceo2.peekNextUrgency());

        // 时间恢复: start() 应用进度 → 第 1 天 Tick 30
        s2.timeManager.start();
        try {
            assertEquals(1, s2.timeManager.dayNumber());
            assertEquals(30, s2.timeManager.tickOfDay());
        } finally {
            s2.timeManager.stop();
        }
    }

    @Test
    void testSaveRestoreStateAndFields() {
        StateStore store = new StateStore(tmp.resolve("state.json").toString());
        AgentSystem s1 = make("HR");
        AgentRole hr = s1.getRole("HR");
        hr.setState(Types.AgentState.OFF_DUTY);
        hr.personality = "存档覆盖的性格";
        hr.skills = java.util.List.of("存档技能A", "存档技能B");
        store.save(s1);

        AgentSystem s2 = make("HR");
        store.restore(s2);
        AgentRole hr2 = s2.getRole("HR");
        assertEquals(Types.AgentState.OFF_DUTY, hr2.state);
        assertEquals("存档覆盖的性格", hr2.personality);
        assertEquals(java.util.List.of("存档技能A", "存档技能B"), hr2.skills);
    }

    @Test
    void testComputerRestoreLocal() {
        StateStore store = new StateStore(tmp.resolve("state.json").toString());
        AgentSystem s1 = make("architect");
        AgentRole role = s1.getRole("architect");
        Computer comp = ComputerManager.createComputer("local", "architect", false,
                new LinkedHashMap<>());
        ComputerManager.getInstance().register(comp, role.name);
        role.bindComputer(comp);
        store.save(s1);

        AgentSystem s2 = make("architect");
        store.restore(s2);
        AgentRole role2 = s2.getRole("architect");
        assertTrue(role2.computerIfCreated() != null);
        assertTrue(role2.computerIfCreated() instanceof Computer.LocalComputer);
        assertEquals("architect", role2.computerIfCreated().roleId);
    }

    @Test
    void testRestoreNoState() {
        AgentSystem s = make("CEO");
        StateStore store = new StateStore(tmp.resolve("state.json").toString());
        assertEquals(0, store.restore(s));
    }
}
