package com.agent.software.company;

import com.agent.software.agent.Agent;
import com.agent.software.agent.AgentState;
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
import com.agent.software.tool.client.ConsoleClientChannel;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟时钟与班次反应的端到端测试。
 *
 * <p>master 对应 {@code AgentSystemTimeTest}：空闲语义（排队工作让时钟保持运转 /
 * 班次结束后的遗留队列不阻塞跨天）、收尾兜底 {@code forceWrapUp()}、以及 SHIFT_END
 * 解阻塞等待者。新架构里这些分别落在 {@code Team}（Sensors）、
 * {@code ShiftDirector}（班次反应）与 {@code ClockDriver}（单步驱动）。
 *
 * <p>时钟不启线程，全部用 {@code tickOnce()} 单步驱动，因此完全确定性。
 */
class CompanyTimeTest {

    @TempDir
    Path dataDir;

    /** 假 LLM：只给最终答案，从不调用 summary —— 用来观察"收尾未完成"的兜底路径。 */
    private static final class PlainLlm implements LlmClient {

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatReply chat(ChatRequest request) {
            calls.incrementAndGet();
            return new ChatReply("好的。", null, 3);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            calls.incrementAndGet();
            return new ToolReply("收到，任务已完成。", null, List.of(), 3);
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
                // 快进阈值为 0：单步驱动时"全员空闲 → 立即快进到下一个触发点"
                new AppConfig.Schedule(1.0, 8, 18, 0L, 1.0, 600_000L),
                new AppConfig.Storage(dir.toString()),
                new AppConfig.Web("127.0.0.1", 0, 60_000L),
                new AppConfig.Mail("agentsoftware.local", new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                new AppConfig.Toolkits(Set.of("memory", "note", "task_view", "time")));
    }

    private RoleSpec spec() {
        return RoleSpec.builder()
                .id(new RoleId("worker"))
                .name("工人")
                .username("worker")
                .title("工程师")
                .personality("务实")
                .group("研发组")
                .skills(List.of("Java"))
                .interestKeywords(Set.of("bug"))
                .toolkits(Set.of("memory", "note", "task_view", "time"))
                .build();
    }

    private Company company(PlainLlm llm) {
        AppConfig config = config(dataDir);
        return new CompanyBuilder(config, AppPaths.resolve(config.storage()), new JacksonJsonCodec())
                .withLlm(llm)
                .withClientChannel(new ConsoleClientChannel())
                .withTranscript(new ChatFeed())
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

    // ── 上班边界 ───────────────────────────────────────────────

    @Test
    void 上班广播后全员上岗并执行上班任务() {
        Company company = company(new PlainLlm());
        try {
            company.staffing().hire(spec());
            company.team().startAll();
            Agent agent = company.team().agent(new RoleId("worker")).orElseThrow();

            company.driver().tickOnce();

            assertEquals(1, company.clock().nowDay().day());
            assertEquals(AgentState.ON_DUTY_IDLE, agent.state(), "SHIFT_START 后应上岗");
            await("SHIFT_START 任务执行完成", 5_000, () -> !agent.history(0).isEmpty());
            assertTrue(agent.history(1).get(0).description().contains("SHIFT_START"),
                    "第一个任务应来自 SHIFT_START 广播");
            await("回到空闲", 5_000, () -> !agent.busy() && agent.queueDepth() == 0);
        } finally {
            company.stop();
        }
    }

    // ── 空闲语义 ───────────────────────────────────────────────

    @Test
    void 班次内排队工作让时钟保持运转() {
        Company company = company(new PlainLlm());
        try {
            company.staffing().hire(spec());
            // 不起时钟线程，也不起 worker：Sensors 直接可观察
            Agent agent = company.team().agent(new RoleId("worker")).orElseThrow();
            assertTrue(company.team().allIdle(), "空队列且在岗 → 空闲");

            agent.submit(task("review the design"), false);
            assertFalse(company.team().allIdle(), "班次内排队的任务应让时钟保持运转");

            // 把时钟推到班次结束：遗留队列由下一班处理，不再算"还在忙"
            company.clock().jumpTo(company.clock().calendar()
                    .at(1, company.clock().calendar().shiftEndTick()));
            assertTrue(company.team().allIdle(), "班次结束后的遗留队列不应阻塞时钟");
            assertFalse(company.team().allOffDuty());
            agent.stateMachine().toOffDuty();
            assertTrue(company.team().allOffDuty());
        } finally {
            company.stop();
        }
    }

    // ── 下班边界 + 收尾兜底 + 跨天 ─────────────────────────────

    @Test
    void 下班收尾超时强制下班并跨天() {
        Company company = company(new PlainLlm());
        try {
            company.staffing().hire(spec());
            company.team().startAll();
            Agent agent = company.team().agent(new RoleId("worker")).orElseThrow();

            // 上班 → 跑完上班任务
            company.driver().tickOnce();
            await("SHIFT_START 任务执行完成", 5_000, () -> !agent.history(0).isEmpty());
            await("回到空闲", 5_000, () -> !agent.busy() && agent.queueDepth() == 0);

            // 全员空闲 → 快进到 18:00，再一步触发下班
            company.driver().tickOnce();
            assertTrue(company.clock().nowDay().tickOfDay() >= company.clock().calendar().shiftEndTick(),
                    "全员空闲时时钟应快进到班次结束，实际 tick=" + company.clock().nowDay().tickOfDay());
            company.driver().tickOnce();

            // SHIFT_END 任务被自动执行，但假 LLM 不写总结 → 角色仍未下班
            await("SHIFT_END 任务执行完成", 5_000,
                    () -> agent.history(0).stream().anyMatch(t -> t.description().contains("SHIFT_END")));
            await("SHIFT_END 任务结束", 5_000, () -> !agent.busy());
            assertNotEquals(AgentState.OFF_DUTY, agent.state(), "没写总结不应自动下班");
            assertFalse(company.team().allOffDuty());

            // 收尾宽限期过 → forceWrapUp 兜底把仍不下班的角色强制 OFF_DUTY
            company.director().forceWrapUp();
            assertEquals(AgentState.OFF_DUTY, agent.state());
            assertTrue(company.team().allOffDuty());

            // 全员下班 → 跨天到第 2 天 08:00
            company.driver().tickOnce();
            company.driver().tickOnce();
            assertEquals(2, company.clock().nowDay().day(), "全员 OFF_DUTY 后应跨天到第 2 天");
            assertEquals(0, company.clock().nowDay().tickOfDay(), "新班次从 08:00 开始");
            assertEquals(AgentState.ON_DUTY_IDLE, agent.state(), "新班次开始应重新上岗");
        } finally {
            company.stop();
        }
    }

    @Test
    void 班次结束后遗留队列不阻塞跨天() {
        Company company = company(new PlainLlm());
        try {
            company.staffing().hire(spec());
            company.team().startAll();
            Agent agent = company.team().agent(new RoleId("worker")).orElseThrow();

            company.driver().tickOnce();
            await("SHIFT_START 任务执行完成", 5_000, () -> !agent.history(0).isEmpty());
            await("回到空闲", 5_000, () -> !agent.busy() && agent.queueDepth() == 0);

            // 快进到班次结束，标记全员下班，再塞一条遗留任务
            company.driver().tickOnce();
            agent.stateMachine().toOffDuty();
            agent.submit(task("遗留任务，明天再干"), false);
            assertTrue(company.team().allIdle());
            assertTrue(company.team().allOffDuty());

            company.driver().tickOnce();   // 快进到次日 08:00
            company.driver().tickOnce();   // 新班次上岗
            assertEquals(2, company.clock().nowDay().day());
            assertEquals(AgentState.ON_DUTY_IDLE, agent.state());
        } finally {
            company.stop();
        }
    }

    // ── SHIFT_END 解阻塞等待者 ─────────────────────────────────

    @Test
    void 下班解阻塞等待中的角色() {
        Company company = company(new PlainLlm());
        try {
            company.staffing().hire(spec());
            company.team().startAll();
            Agent agent = company.team().agent(new RoleId("worker")).orElseThrow();

            company.driver().tickOnce();
            await("SHIFT_START 任务执行完成", 5_000, () -> !agent.history(0).isEmpty());
            await("回到空闲", 5_000, () -> !agent.busy() && agent.queueDepth() == 0);

            // 角色阻塞在等同事回复（talk wait=true）
            agent.waits().begin(new RoleId("colleague"));
            assertTrue(agent.waits().waiting());

            company.driver().tickOnce();   // 快进到 18:00
            company.driver().tickOnce();   // 触发 SHIFT_END → 解阻塞

            assertTrue(agent.waits().hasReply(), "下班应给等待者投一条合成回复");
            assertFalse(agent.waits().await(Duration.ofMillis(1)).isEmpty());
            agent.waits().end();
        } finally {
            company.stop();
        }
    }
}
