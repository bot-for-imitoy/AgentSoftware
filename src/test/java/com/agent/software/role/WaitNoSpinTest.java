package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import com.agent.software.io.WebInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 等回复期间（WAIT）不得忙等空转。
 *
 * <p>worker 用 {@code pollEvent(200)} 取事件，而队列非空时它会立即返回 —— 如果 WAIT 时还去取，
 * {@code runTask} 会"取出→塞回"无限循环，把一个核烧到 100%。这里验证：WAIT 期间任务留在队列里
 * 不被消费、进程 CPU 基本不动；恢复后恰好处理一次（不丢不重）。
 */
class WaitNoSpinTest {

    /** 观测窗口与 CPU 预算：真忙等会烧掉整窗口，修复后只睡不烧。 */
    private static final long WINDOW_MILLIS = 400;
    private static final long CPU_BUDGET_MILLIS = 200;

    @Test
    void aWaitingRoleDoesNotBusySpinAndHandlesTheTaskOnceResumed(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            ceo.setLlm(new FakeLlm());

            ceo.beginWait("architect");                 // 模拟"正在等某人回复"
            assertTrue(ceo.isWaiting());
            ceo.enqueue(new Task("system", "CEO", 0, "queued while waiting", Priority.NORMAL));

            Long cpuBefore = processCpuNanos();
            Thread.sleep(WINDOW_MILLIS);
            Long cpuAfter = processCpuNanos();

            // 等待期间不得消费任务
            assertEquals(1, ceo.queueDepth(), "WAIT 期间任务应留在队列里");
            assertEquals(0, wakes(ceo), "WAIT 期间不应跑任何任务: " + ceo.readJournal());

            // 不得忙等空转
            if (cpuBefore != null && cpuAfter != null) {
                long usedMillis = (cpuAfter - cpuBefore) / 1_000_000L;
                assertTrue(usedMillis < CPU_BUDGET_MILLIS,
                        "WAIT 期间不应忙等空转：观测窗口 " + WINDOW_MILLIS + "ms 内进程用了 "
                                + usedMillis + "ms CPU");
            }

            // 恢复后恰好处理一次
            ceo.endWait();
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && wakes(ceo) == 0) {
                Thread.sleep(20);
            }
            assertEquals(1, wakes(ceo), "恢复后应处理一次: " + ceo.readJournal());
            assertEquals(0, ceo.queueDepth());
        } finally {
            system.stop();
        }
    }

    /** 进程 CPU 时间；平台不支持时返回 null（跳过该断言）。 */
    private static Long processCpuNanos() {
        java.lang.management.OperatingSystemMXBean bean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            long t = sun.getProcessCpuTime();
            return t < 0 ? null : t;
        }
        return null;
    }

    private static long wakes(Role role) {
        return role.readJournal().stream().filter(l -> l.contains("answer(")).count();
    }

    /** 立即返回的假 LLM，避免测试里真的发 HTTP。 */
    private static final class FakeLlm extends com.agent.software.llm.LLM {
        @Override
        public String getModel() {
            return "fake";
        }

        @Override
        public String getEndpoint() {
            return "fake";
        }

        @Override
        public com.agent.software.llm.Response request() {
            return new com.agent.software.llm.Response("ok", "", List.of(), 0);
        }
    }
}
