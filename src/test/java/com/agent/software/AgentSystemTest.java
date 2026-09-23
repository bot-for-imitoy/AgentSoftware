package com.agent.software;

import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    @Test
    void shiftEndInterruptsClientWait(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            CompletableFuture<String> talking = CompletableFuture.supplyAsync(
                    () -> system.getClientChannel().receiveFrom("CEO", "hello client"));
            Thread.sleep(100);

            ceo.onShiftEnd();   // 时间线程在下班时做的事

            String result = talking.get(3, TimeUnit.SECONDS);
            assertTrue(result.contains("shift end"), result);
            assertTrue(system.getClientChannel().isFree(), "下班后客户通道要释放");
        } finally {
            system.stop();
        }
    }
}
