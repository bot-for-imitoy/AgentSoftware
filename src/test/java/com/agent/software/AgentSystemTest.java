package com.agent.software;

import com.agent.software.io.WebInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 组合根：默认大组、暂停/恢复、多实例隔离。 */
class AgentSystemTest {

    @Test
    void startsWithManagementCohort(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            assertEquals(5, system.getRolePool().size());
            assertEquals(54, system.getRoster().size());
            assertEquals(5, system.getComputerManager().all().size());
            assertTrue(system.getTimeBus().isWorkingHours());
        } finally {
            system.stop();
        }
    }

    @Test
    void pauseAndResumeFreezeTheClock(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            system.pause();
            assertTrue(system.getTimeBus().isPaused());
            system.resume();
            assertFalse(system.getTimeBus().isPaused());
        } finally {
            system.stop();
        }
    }

    @Test
    void twoSystemsAreIsolated(@TempDir Path dirA, @TempDir Path dirB) {
        AgentSystem a = new AgentSystem(dirA, new WebInput());
        AgentSystem b = new AgentSystem(dirB, new WebInput());
        try {
            assertNotSame(a.getRolePool(), b.getRolePool());
            assertNotSame(a.getRoster(), b.getRoster());
            assertNotSame(a.getComputerManager(), b.getComputerManager());
            assertEquals(5, a.getRolePool().size());
            assertEquals(5, b.getRolePool().size());

            a.getStaffing().draftIn("architect");
            assertEquals(6, a.getRolePool().size());
            assertEquals(5, b.getRolePool().size(), "另一个实例不受影响");
        } finally {
            a.stop();
            b.stop();
        }
    }
}
