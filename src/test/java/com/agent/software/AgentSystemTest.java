package com.agent.software;

import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import com.agent.software.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
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

    @Test
    void configIsReadFromConfiguredConfigDir(@TempDir Path cfgDir, @TempDir Path dataDir) {
        System.setProperty("AGENTSOFTWARE_CONFIG_DIR", cfgDir.toString());
        try {
            Json.writeFile(cfgDir.resolve("config.json"),
                    Map.of("llm", Map.of("model", "test-model", "base_url", "http://example.invalid")));

            AgentSystem system = new AgentSystem(dataDir, new WebInput());
            try {
                assertEquals(cfgDir.resolve("config.json"), system.getConfigStore().getPath());
                assertEquals("test-model", system.getConfigStore().get("llm.model", null));
                assertEquals("http://example.invalid", system.getConfigStore().get("llm.base_url", null));
            } finally {
                system.stop();
            }
        } finally {
            System.clearProperty("AGENTSOFTWARE_CONFIG_DIR");
        }
    }

    @Test
    void timeScaleComesFromSystemProperty(@TempDir Path dir) {
        System.setProperty("agentsoftware.timeScale", "20");
        try {
            AgentSystem system = new AgentSystem(dir, new WebInput());
            try {
                assertEquals(20.0, system.getTimeBus().getTimeScale(), 1e-9);
            } finally {
                system.stop();
            }
        } finally {
            System.clearProperty("agentsoftware.timeScale");
        }
    }

    @Test
    void autoPausesWhenThereIsNoFurtherWork(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            system.start();
            long deadline = System.currentTimeMillis() + 8_000;
            while (System.currentTimeMillis() < deadline && !system.getTimeBus().isPaused()) {
                Thread.sleep(50);
            }
            assertTrue(system.getTimeBus().isPaused(), "没有后续任务时应自动 pause");
        } finally {
            system.stop();
        }
    }

    @Test
    void doesNotPauseWhileATaskIsStillScheduled(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            // 排一个未来的事件：时钟应快进过去处理它，而不是在开局就 pause
            system.getEventBus().schedule(com.agent.software.event.Event.builder()
                    .to("CEO").type(com.agent.software.event.EventType.CUSTOM)
                    .priority(com.agent.software.event.Priority.NORMAL)
                    .at(5_000).content("later").build());
            system.start();

            long deadline = System.currentTimeMillis() + 6_000;
            while (System.currentTimeMillis() < deadline && system.getTimeBus().now() < 5_000) {
                Thread.sleep(50);
            }
            assertTrue(system.getTimeBus().now() >= 5_000, "应快进到排期事件的时间点");
        } finally {
            system.stop();
        }
    }
}
