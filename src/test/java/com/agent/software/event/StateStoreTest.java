package com.agent.software.event;

import com.agent.software.AgentSystem;
import com.agent.software.computers.Computer;
import com.agent.software.computers.ComputerManager;
import com.agent.software.core.Types;
import com.agent.software.io.StdInput;
import com.agent.software.role.AgentRole;
import com.agent.software.store.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unified state store (StateStore) tests (the Java counterpart of the Python test_state_store.py).
 */
class StateStoreTest {

    @TempDir
    Path tmp;

    private AgentSystem make(String rid) {
        AgentRole.JOURNAL_DIR = tmp.resolve("journals");
        return new AgentSystem(null, java.util.List.of(rid), 30.0, false, new StdInput());
    }

    @Test
    void testSaveRestoreRoundtrip() {
        StateStore store = new StateStore(tmp.resolve("state.json").toString());
        AgentSystem s1 = make("CEO");
        AgentRole ceo = s1.getRole("CEO");
        AgentRole.Task done = new AgentRole.Task(AgentRole.Urgency.HIGH.value, "Completed the login page development", "github",
                new LinkedHashMap<>());
        done.status = "done";
        done.result = "Delivered";
        done.tokensConsumed = 456;
        ceo.appendTaskHistory(done);
        ceo.addTask(new AgentRole.Task(AgentRole.Urgency.NORMAL.value, "Todo: write weekly report", "", new LinkedHashMap<>()));
        s1.timeManager.debugSetTick(30);  // Day 1 Tick 30
        store.save(s1);

        AgentSystem s2 = make("CEO");
        assertEquals(1, store.restore(s2));
        AgentRole ceo2 = s2.getRole("CEO");
        assertEquals("Completed the login page development", ceo2.taskHistory(null).get(0).description);
        assertEquals(456, ceo2.taskHistory(null).get(0).tokensConsumed);
        assertEquals(1, ceo2.queueDepth());
        assertEquals(AgentRole.Urgency.NORMAL, ceo2.peekNextUrgency());

        // time restore: start() applies progress → Day 1 Tick 30
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
        hr.personality = "Personality overridden by the archive";
        hr.skills = java.util.List.of("Archived skill A", "Archived skill B");
        store.save(s1);

        AgentSystem s2 = make("HR");
        store.restore(s2);
        AgentRole hr2 = s2.getRole("HR");
        assertEquals(Types.AgentState.OFF_DUTY, hr2.state);
        assertEquals("Personality overridden by the archive", hr2.personality);
        assertEquals(java.util.List.of("Archived skill A", "Archived skill B"), hr2.skills);
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
