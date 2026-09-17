package com.agent.software.company;

import com.agent.software.agent.Agent;
import com.agent.software.agent.LifecycleGate;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.bootstrap.CompanyBuilder;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import com.agent.software.transcript.ChatFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局暂停 / 恢复。
 *
 * <p>master 对应 {@code AgentSystemPauseTest}：暂停后 worker 不取新任务、LLM 不发请求、
 * 恢复后继续处理遗留任务。新架构把"暂停"从 {@code AgentSystem} 的私有字段收进
 * {@link LifecycleGate}：{@code TaskRunner} 在取任务前问它，LLM 适配器也问它；
 * {@link Company#pause(String)} 同时冻结 {@code ClockDriver}。
 *
 * <p>master 还会往 Web 聊天流写"System paused / System resumed"通知；新架构的
 * {@code Company.pause/resume} 只做门控，不再产生通知（见报告）。
 */
class CompanyPauseTest {

    @TempDir
    Path dataDir;

    /** 计数型假 LLM：记录被调用次数，用来观察"暂停期间不发请求"。 */
    private static final class CountingLlm implements LlmClient {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatReply chat(ChatRequest request) {
            calls.incrementAndGet();
            return new ChatReply("完成", null, 1);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            calls.incrementAndGet();
            return new ToolReply("完成", null, List.of(), 1);
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            return new ChatReply("（摘要）", null, 1);
        }
    }

    private AppConfig config(Path dir) {
        return new AppConfig(
                new AppConfig.Llm("openai", "test-model", "k", "http://localhost:1",
                        new AppConfig.Llm.Retry(1, 0.01, 5)),
                new AppConfig.Schedule(1.0, 8, 18, 0L, 1.0, 600_000L),
                new AppConfig.Storage(dir.toString()),
                new AppConfig.Web("127.0.0.1", 0, 60_000L),
                new AppConfig.Mail("agentsoftware.local", new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                new AppConfig.Toolkits(Set.of("memory", "note", "task_view", "time")));
    }

    private Company company(CountingLlm llm) {
        AppConfig config = config(dataDir);
        return new CompanyBuilder(config, AppPaths.resolve(config.storage()), new JacksonJsonCodec())
                .withLlm(llm)
                .withTranscript(new ChatFeed())
                .build();
    }

    /** 不挂任何工具包 → ToolLoop 走单轮 {@code chat}，一次任务恰好一次 LLM 调用。 */
    private static RoleSpec spec() {
        return RoleSpec.builder()
                .id(new RoleId("worker"))
                .name("工人")
                .username("worker")
                .title("工程师")
                .group("研发组")
                .skills(List.of("Java"))
                .toolkits(Set.of())
                .build();
    }

    private static Task task(String description) {
        return new Task(TaskId.generate(), Priority.NORMAL, description,
                new EventKind("test", "TASK"), Payload.empty(), Instant.now(), null);
    }

    private static void await(String what, long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "等待超时：" + what);
    }

    // ── 暂停状态 ───────────────────────────────────────────────

    @Test
    void pause状态迁移与时钟暂停开关() {
        Company company = company(new CountingLlm());
        try {
            assertFalse(company.paused());
            assertEquals("", company.pauseReason());
            assertFalse(company.driver().paused());

            company.pause("余额不足（HTTP 402）");
            assertTrue(company.paused());
            assertEquals("余额不足（HTTP 402）", company.pauseReason());
            assertTrue(company.driver().paused(), "暂停应同时冻结时钟");

            // 重复 pause 只刷新原因
            company.pause("第二个原因");
            assertTrue(company.paused());
            assertEquals("第二个原因", company.pauseReason());

            company.resume();
            assertFalse(company.paused());
            assertEquals("", company.pauseReason());
            assertFalse(company.driver().paused());

            // 未暂停时 resume 是 no-op
            company.resume();
            assertFalse(company.paused());
        } finally {
            company.stop();
        }
    }

    // ── LifecycleGate ──────────────────────────────────────────

    @Test
    void LifecycleGate暂停时挂起调用线程() throws Exception {
        LifecycleGate gate = new LifecycleGate();
        assertFalse(gate.paused());

        gate.pause("测试暂停");
        assertTrue(gate.paused());
        assertEquals("测试暂停", gate.reason());

        AtomicInteger returned = new AtomicInteger();
        Thread caller = new Thread(() -> {
            gate.awaitRunning(Duration.ofMillis(50));
            returned.incrementAndGet();
        });
        caller.start();
        try {
            Thread.sleep(250);
            assertEquals(0, returned.get(), "暂停期间 awaitRunning 不应返回");

            gate.resume();
            caller.join(2_000);
            assertFalse(caller.isAlive());
            assertEquals(1, returned.get(), "恢复后等待应立即结束");
            assertFalse(gate.paused());
            assertEquals("", gate.reason());
        } finally {
            gate.resume();
            caller.join(1_000);
        }
    }

    // ── worker 挂起 ────────────────────────────────────────────

    @Test
    void 暂停时worker不取新任务恢复后继续() {
        CountingLlm llm = new CountingLlm();
        Company company = company(llm);
        try {
            company.staffing().hire(spec());
            company.team().startAll();
            Agent agent = company.team().agent(new RoleId("worker")).orElseThrow();

            // 运行中：任务会被执行
            int before = llm.calls.get();
            agent.submit(task("task 1 while running"), false);
            await("运行中执行任务", 5_000, () -> llm.calls.get() > before);
            await("任务执行完毕", 5_000, () -> !agent.busy() && agent.mailbox().readyDepth() == 0);
            int running = llm.calls.get();

            // 暂停后再入队：worker 不应取任务，也不应有 LLM 请求
            company.pause("测试暂停");
            assertTrue(company.paused());
            agent.submit(task("task 2 while paused"), false);
            await("任务进入可执行队列", 2_000, () -> agent.mailbox().readyDepth() == 1);

            Thread.sleep(300);
            assertEquals(running, llm.calls.get(), "暂停期间不应发起 LLM 请求");
            assertEquals(1, agent.mailbox().readyDepth(), "暂停期间任务应留在队列里");

            // 恢复后继续处理遗留任务
            company.resume();
            await("恢复后执行遗留任务", 5_000, () -> llm.calls.get() > running);
            await("队列清空", 5_000, () -> agent.queueDepth() == 0 && !agent.busy());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("测试被中断", e);
        } finally {
            company.stop();
        }
    }
}
