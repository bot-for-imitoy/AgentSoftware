package com.agent.software.agent.dispatch;

import com.agent.software.agent.Agent;
import com.agent.software.agent.Team;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryContext;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryDecision;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryVerdict;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.bootstrap.CompanyBuilder;
import com.agent.software.company.Company;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.sim.clock.ScheduleTable;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import com.agent.software.transcript.ChatFeed;
import com.agent.software.transcript.Transcript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EventRouter} 事件路由测试。
 *
 * <p>迁移自 master 的 {@code EventBusTest}：master 的 {@code EventBus}/{@code TimeEventBus}
 * 既当调度表又当投递器；新架构里调度归 {@link ScheduleTable}（见
 * {@code ScheduleTableTest}），"谁在什么时候能收到这个事件"归 {@code EventRouter}。
 * 这里验证广播逐个角色决策、定向只投目标、目标不存在返回空、{@code publishAndReport}
 * 的 verdict/taskId、以及 DELIVER 进 ready / HOLD 进 deferred。
 *
 * <p>用假的 {@link DeliveryPolicy} 决定每个角色的取舍，因此不需要真实 LLM 或线程；
 * 角色用 {@code CompanyBuilder} 装配（不启动 worker）。
 */
class EventRouterTest {

    @TempDir
    Path dataDir;

    private Team team;
    private ScheduleTable schedule;
    private ChatFeed feed;

    /** 不干活的 LLM：装配时占位，测试不会真的调用。 */
    private static final class NoopLlm implements LlmClient {
        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("", null, 0);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            return new ToolReply("", null, List.of(), 0);
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            return new ChatReply("", null, 0);
        }
    }

    private static RoleSpec spec(String id) {
        return RoleSpec.builder()
                .id(new RoleId(id)).name(id).username(id)
                .toolkits(Set.of())
                .build();
    }

    /** 装配一个不启动 worker 的公司，只取花名册、日程表与轨迹。 */
    private void assemble(RoleSpec... specs) {
        AppConfig config = new AppConfig(
                new AppConfig.Llm("openai", "test", "k", "http://localhost:1",
                        new AppConfig.Llm.Retry(1, 0.01, 5)),
                new AppConfig.Schedule(1.0, 8, 18, 0L, 1.0, 600_000L),
                new AppConfig.Storage(dataDir.toString()),
                new AppConfig.Web("127.0.0.1", 0, 60_000L),
                new AppConfig.Mail("agentsoftware.local",
                        new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                new AppConfig.Toolkits(Set.of()));
        feed = new ChatFeed();
        Company company = new CompanyBuilder(config, AppPaths.resolve(config.storage()),
                new JacksonJsonCodec())
                .withLlm(new NoopLlm())
                .withTranscript(feed)
                .build();
        for (RoleSpec role : specs) {
            company.staffing().hire(role);
        }
        team = company.team();
        schedule = company.schedule();
    }

    private EventRouter router(DeliveryPolicy policy) {
        return new EventRouter(team, policy, new TaskFactory(schedule), feed);
    }

    // ── 广播逐个角色决策 ────────────────────────────────────────

    @Test
    void 广播事件逐个角色决策并分别入队() {
        assemble(spec("ceo"), spec("cto"));
        List<DeliveryContext> seen = new ArrayList<>();
        EventRouter router = router(context -> {
            seen.add(context);
            return context.spec().id().value().equals("ceo")
                    ? DeliveryDecision.deliver("ceo 通过")
                    : DeliveryDecision.hold("cto 暂存");
        });

        Map<RoleId, EventRouter.Routed> result = router.publishAndReport(
                AgentEvent.broadcast(EventKind.NEW_MAIL, Priority.NORMAL, Payload.of("title", "需求")));

        assertEquals(Set.of(new RoleId("ceo"), new RoleId("cto")), result.keySet());
        assertEquals(2, seen.size(), "广播必须对每个角色各做一次决策");
        assertEquals(DeliveryVerdict.DELIVER, result.get(new RoleId("ceo")).verdict());
        assertEquals(DeliveryVerdict.HOLD, result.get(new RoleId("cto")).verdict());
        assertNotNull(result.get(new RoleId("ceo")).taskId());
        assertNotNull(result.get(new RoleId("cto")).taskId());

        Agent ceo = team.agent(new RoleId("ceo")).orElseThrow();
        Agent cto = team.agent(new RoleId("cto")).orElseThrow();
        assertEquals(1, ceo.mailbox().readyDepth(), "DELIVER 进可执行队列");
        assertEquals(0, ceo.mailbox().deferredDepth());
        assertEquals(0, cto.mailbox().readyDepth());
        assertEquals(1, cto.mailbox().deferredDepth(), "HOLD 进暂存队列");
        assertEquals(result.get(new RoleId("ceo")).taskId(), ceo.mailbox().peek().orElseThrow().id(),
                "返回的 taskId 就是真正入队的那个任务");
    }

    @Test
    void publish与publishAndReport等价投递() {
        assemble(spec("ceo"));
        EventRouter router = router(context -> DeliveryDecision.deliver("ok"));

        router.publish(AgentEvent.broadcast(EventKind.NEW_MAIL, Priority.NORMAL, Payload.empty()));

        assertEquals(1, team.agent(new RoleId("ceo")).orElseThrow().mailbox().readyDepth());
    }

    // ── 定向只投目标 ────────────────────────────────────────────

    @Test
    void 定向事件只投递给目标角色() {
        assemble(spec("ceo"), spec("cto"));
        List<DeliveryContext> seen = new ArrayList<>();
        EventRouter router = router(context -> {
            seen.add(context);
            return DeliveryDecision.deliver("ok");
        });

        Map<RoleId, EventRouter.Routed> result = router.publishAndReport(
                AgentEvent.toRole(new RoleId("cto"), EventKind.NEW_MAIL, Priority.NORMAL, Payload.empty()));

        assertEquals(Set.of(new RoleId("cto")), result.keySet());
        assertEquals(1, seen.size());
        assertEquals(0, team.agent(new RoleId("ceo")).orElseThrow().queueDepth());
        assertEquals(1, team.agent(new RoleId("cto")).orElseThrow().queueDepth());
    }

    @Test
    void 目标角色不存在时返回空结果且不投递() {
        assemble(spec("ceo"));
        EventRouter router = router(context -> DeliveryDecision.deliver("ok"));

        Map<RoleId, EventRouter.Routed> result = router.publishAndReport(
                AgentEvent.toRole(new RoleId("ghost"), EventKind.NEW_MAIL, Priority.NORMAL, Payload.empty()));

        assertTrue(result.isEmpty());
        assertEquals(0, team.agent(new RoleId("ceo")).orElseThrow().queueDepth());
    }

    // ── DROP 不入队 ─────────────────────────────────────────────

    @Test
    void 丢弃裁决不入队且taskId为空() {
        assemble(spec("ceo"));
        EventRouter router = router(context -> DeliveryDecision.drop("不相关"));

        Map<RoleId, EventRouter.Routed> result = router.publishAndReport(
                AgentEvent.broadcast(EventKind.NEW_MAIL, Priority.NORMAL, Payload.empty()));

        EventRouter.Routed routed = result.get(new RoleId("ceo"));
        assertEquals(DeliveryVerdict.DROP, routed.verdict());
        assertNull(routed.taskId());
        assertEquals(0, team.agent(new RoleId("ceo")).orElseThrow().queueDepth());
    }

    // ── 定时提醒标记 + 轨迹 ─────────────────────────────────────

    @Test
    void TASK_DUE事件被标记为定时提醒() {
        assemble(spec("ceo"));
        List<Boolean> reminderFlags = new ArrayList<>();
        List<EventKind> kinds = new ArrayList<>();
        EventRouter router = router(context -> {
            reminderFlags.add(context.scheduledReminder());
            kinds.add(context.event().kind());
            return DeliveryDecision.deliver("ok");
        });

        router.publishAndReport(AgentEvent.toRole(new RoleId("ceo"), EventKind.TASK_DUE,
                Priority.NORMAL, Payload.empty()));
        router.publishAndReport(AgentEvent.toRole(new RoleId("ceo"), EventKind.NEW_MAIL,
                Priority.NORMAL, Payload.empty()));

        assertEquals(List.of(true, false), reminderFlags);
        assertEquals(List.of(EventKind.TASK_DUE, EventKind.NEW_MAIL), kinds);
    }

    @Test
    void 空事件返回空结果() {
        assemble(spec("ceo"));
        EventRouter router = router(context -> DeliveryDecision.deliver("ok"));
        assertTrue(router.publishAndReport(null).isEmpty());
    }

    @Test
    void 路由结果写入轨迹() {
        assemble(spec("ceo"));
        EventRouter router = router(context -> DeliveryDecision.deliver("ok"));

        router.publishAndReport(AgentEvent.broadcast(EventKind.NEW_MAIL, Priority.NORMAL, Payload.empty()));

        List<Transcript.Entry> entries = feed.since(0);
        assertEquals(1, entries.size());
        assertEquals(ChatFeed.KIND_SYSTEM, entries.get(0).kind());
        assertTrue(entries.get(0).text().contains("NEW_MAIL"), entries.get(0).text());
        assertTrue(entries.get(0).text().contains("投递 1"), entries.get(0).text());
    }
}
