package com.agent.software.company;

import com.agent.software.agent.Agent;
import com.agent.software.agent.AgentState;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.bootstrap.CompanyBuilder;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.llm.Message;
import com.agent.software.llm.ToolCallRequest;
import com.agent.software.sim.clock.DefaultClockPolicy;
import com.agent.software.sim.clock.ScheduleTable;
import com.agent.software.sim.clock.ShiftCalendar;
import com.agent.software.sim.clock.SimClock;
import com.agent.software.tool.client.ConsoleClientChannel;
import com.agent.software.transcript.ChatFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一整天（上班 → 干活 → 下班写总结 → 跨天）的端到端测试。
 *
 * <p>它是整个重写的"接缝测试"：把装配（bootstrap）、时钟（sim）、班次反应（company）、
 * 投递（dispatch）、执行（task）与工具（tool）全部串起来，任何一个环的语义错了都会在这里暴露。
 * 时钟不启线程，用 {@code tickOnce()} 单步驱动，因此完全确定性。
 */
class CompanyDayCycleTest {

    @TempDir
    Path dataDir;

    /** 假 LLM：收到 SHIFT_END 任务时调用 summary 工具，其余任务直接给答案。 */
    private static final class FakeLlm implements LlmClient {

        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("好的。", null, 5);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            // 只有"还没调用过工具"时才发起 summary：真实模型看到工具回执后就会收尾，
            // 假模型必须模拟这个收敛行为，否则工具循环会一直转到轮次上限。
            boolean alreadyCalledTool = request.messages().stream()
                    .anyMatch(message -> message.role() == Message.Role.TOOL);
            boolean shiftEnd = request.messages().stream()
                    .map(Message::content)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(text -> text.contains("SHIFT_END"));
            if (shiftEnd && !alreadyCalledTool) {
                return new ToolReply("", null, List.of(new ToolCallRequest(
                        "call-1", "summary", Payload.of("content", "今天完成了重写与自测。"))), 7);
            }
            return new ToolReply("收到，任务已完成。", null, List.of(), 9);
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            return new ChatReply("（压缩后的历史）", null, 3);
        }
    }

    private AppConfig config(Path dir) {
        return new AppConfig(
                new AppConfig.Llm("openai", "test-model", "k", "http://localhost:1", new AppConfig.Llm.Retry(1, 0.01, 5)),
                // 快进阈值为 0：单步驱动时"全员空闲 → 立即快进到下一个触发点"
                new AppConfig.Schedule(1.0, 8, 18, 0L, 1.0, 600_000L),
                new AppConfig.Storage(dir.toString()),
                new AppConfig.Web("127.0.0.1", 0, 60_000L),
                new AppConfig.Mail("agentsoftware.local", new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                new AppConfig.Toolkits(Set.of("memory", "note", "task_view", "time")));
    }

    private RoleSpec spec() {
        return RoleSpec.builder()
                .id(new RoleId("coder"))
                .name("张三")
                .username("zhangsan")
                .title("工程师")
                .personality("务实")
                .group("研发组")
                .skills(List.of("Java"))
                .interestKeywords(Set.of("bug", "feature"))
                .toolkits(Set.of("memory", "note", "task_view", "time"))
                .build();
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

    @Test
    void 一整天从上班到跨天() {
        AppConfig config = config(dataDir);
        Company company = new CompanyBuilder(config, AppPaths.resolve(config.storage()),
                new JacksonJsonCodec())
                .withLlm(new FakeLlm())
                .withClientChannel(new ConsoleClientChannel())
                .withTranscript(new ChatFeed())
                .build();
        company.staffing().hire(spec());

        // 只起 worker，不起时钟线程：下面手动单步驱动，保证确定性
        company.team().startAll();
        Agent agent = company.team().agent(new RoleId("coder")).orElseThrow();

        // ── 1. 第 1 天 tick 0：SHIFT_START 广播 → 角色收到任务并执行 ──────────
        company.driver().tickOnce();
        assertEquals(1, company.clock().nowDay().day());
        assertEquals(AgentState.ON_DUTY_IDLE, agent.state());
        await("SHIFT_START 任务执行完成", 5_000, () -> agent.history(0).size() >= 1);
        assertTrue(agent.history(1).get(0).description().contains("SHIFT_START"),
                "第一个任务应当来自 SHIFT_START 广播");
        assertEquals(0, agent.queueDepth(), "任务执行完队列应当为空");

        // ── 2. 全员空闲 → 快进到 18:00，再一步触发下班 ─────────────────────
        company.driver().tickOnce();
        assertTrue(company.clock().nowDay().tickOfDay() >= company.clock().calendar().shiftEndTick(),
                "全员空闲时时钟应快进到班次结束，实际 tick=" + company.clock().nowDay().tickOfDay());
        company.driver().tickOnce();

        // ── 3. 下班任务 → 角色调用 summary → OFF_DUTY ───────────────────────
        await("SHIFT_END 任务执行完成", 5_000,
                () -> agent.history(0).stream().anyMatch(t -> t.description().contains("SHIFT_END")));
        assertEquals(AgentState.OFF_DUTY, agent.state(), "写了总结后应当下班");
        assertTrue(agent.conversation().isEmpty(), "下班后当日对话应当被冲刷");

        // ── 4. 全员下班 → 跨天到第 2 天 08:00 ─────────────────────────────
        company.driver().tickOnce();
        company.driver().tickOnce();
        assertEquals(2, company.clock().nowDay().day(), "全员 OFF_DUTY 后应跨天到第 2 天");
        assertEquals(AgentState.ON_DUTY_IDLE, agent.state(), "新班次开始应重新上岗");

        company.stop();
    }

    @Test
    void 下班后的普通事件只暂存不执行() {
        AppConfig config = config(dataDir);
        Company company = new CompanyBuilder(config, AppPaths.resolve(config.storage()),
                new JacksonJsonCodec())
                .withLlm(new FakeLlm())
                .withClientChannel(new ConsoleClientChannel())
                .withTranscript(new ChatFeed())
                .build();
        company.staffing().hire(spec());
        company.team().startAll();
        Agent agent = company.team().agent(new RoleId("coder")).orElseThrow();

        // 先把上班任务跑完
        company.driver().tickOnce();
        await("SHIFT_START 任务执行完成", 5_000, () -> agent.history(0).size() >= 1);

        // 手动把角色置于 OFF_DUTY，再投一个**定向**事件（定向事件不过内容显著性过滤，
        // 因此一定能走到"状态约束"这一层）
        agent.stateMachine().toOffDuty();
        company.publish(com.agent.software.sim.event.AgentEvent.toRole(
                new RoleId("coder"),
                new com.agent.software.sim.event.EventKind("email", "NEW_MAIL"),
                com.agent.software.sim.event.Priority.NORMAL,
                Payload.of("title", "客户催单").with("text", "请尽快处理")));

        assertEquals(1, agent.mailbox().deferredDepth(), "下班后的普通事件应进入暂存队列");
        assertEquals(0, agent.mailbox().readyDepth(), "不应进入可执行队列");

        // 上班时暂存任务被提升
        agent.promoteDeferred();
        assertEquals(0, agent.mailbox().deferredDepth());
        assertEquals(1, agent.mailbox().readyDepth());

        company.stop();
    }

    @Test
    void 时钟与日历的纯函数语义() {
        ShiftCalendar calendar = ShiftCalendar.of(1.0, 8, 18);
        assertEquals(86400, calendar.ticksPerDay());
        assertEquals(36000, calendar.shiftEndTick());
        assertEquals(1, calendar.locate(new com.agent.software.kernel.Tick(0)).day());
        assertEquals(0, calendar.locate(new com.agent.software.kernel.Tick(0)).tickOfDay());
        assertEquals(2, calendar.locate(new com.agent.software.kernel.Tick(86400)).day());
        assertEquals(36000L, calendar.at(1, 36000).value());
        assertEquals("08:00:00", calendar.clockTime(calendar.locate(new com.agent.software.kernel.Tick(0))));
        assertEquals("18:00:00", calendar.clockTime(calendar.locate(new com.agent.software.kernel.Tick(36000))));
        assertTrue(calendar.withinShift(calendar.locate(new com.agent.software.kernel.Tick(0))));
        assertTrue(!calendar.withinShift(calendar.locate(new com.agent.software.kernel.Tick(36000))));
        assertEquals(86400L, calendar.nextShiftStart(calendar.locate(new com.agent.software.kernel.Tick(36000))).value());
    }

    @Test
    void 默认时钟策略的优先级() {
        DefaultClockPolicy policy = new DefaultClockPolicy(60_000L);
        SimClock clock = new SimClock(ShiftCalendar.of(1.0, 8, 18), LocalDate.of(2026, 1, 1));
        var signals = new com.agent.software.sim.clock.ClockPolicy.ClockSignals(
                false, true, false, true, 0L);
        var action = policy.next(signals, clock.calendar(), clock.now(),
                java.util.Optional.of(new com.agent.software.kernel.Tick(36000)));
        assertTrue(action instanceof com.agent.software.sim.clock.ClockPolicy.ClockAction.ForceWrapUp,
                "收尾超时应优先于其它动作");
    }

    @Test
    void 日程表按天激活并到期投递() {
        ScheduleTable schedule = new ScheduleTable();
        assertNotNull(schedule.schedule("提醒开会", new RoleId("coder"),
                new com.agent.software.kernel.DayTick(1, 60), Payload.of("title", "开会")));
        schedule.activateDay(1);
        assertEquals(1, schedule.list(new RoleId("coder")).size());
        assertTrue(schedule.nextFireTick(new com.agent.software.kernel.Tick(0)).isPresent());
        assertEquals(1, schedule.due(new com.agent.software.kernel.Tick(60)).size());
        assertTrue(schedule.list(null).isEmpty(), "触发后不应再出现在未触发列表里");
    }
}
