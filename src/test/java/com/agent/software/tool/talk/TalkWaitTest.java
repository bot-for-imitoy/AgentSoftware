package com.agent.software.tool.talk;

import com.agent.software.agent.Agent;
import com.agent.software.agent.AgentState;
import com.agent.software.agent.Team;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.bootstrap.CompanyBuilder;
import com.agent.software.company.Company;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.llm.LlmClient;
import com.agent.software.tool.client.ConsoleClientChannel;
import com.agent.software.tool.talk.TeamChannel.TalkMessage;
import com.agent.software.transcript.ChatFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TalkService} 的 talk wait=true 同步等待测试
 * （迁移自 master {@code tools/TalkWaitTest}）。
 *
 * <p>新架构把等待协议收进 {@code agent.WaitCoordinator}，回复通路是
 * {@code Task.onComplete} → 目标 {@code Agent.finishTask} → {@code notifyComplete} →
 * 发送方 {@code WaitCoordinator.deliver}。这里用真实的 {@code Team}+{@code Agent}
 * （{@code CompanyBuilder} + 假 LLM）跑通整条回传链路。
 */
class TalkWaitTest {

    @TempDir
    Path dataDir;

    private static final RoleId A = new RoleId("architect");
    private static final RoleId B = new RoleId("tester_1");

    /** 假 LLM：对委派任务直接给答案，不调工具。 */
    private static final class FakeLlm implements LlmClient {

        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("80% 完成，正在自测。", null, 5);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            return new ToolReply("80% 完成，正在自测。", null, List.of(), 6);
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            return new ChatReply("（摘要）", null, 3);
        }
    }

    private static void await(String what, long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "等待超时：" + what);
    }

    private static RoleSpec spec(RoleId id, String name, String username, String group) {
        return RoleSpec.builder()
                .id(id).name(name).username(username).group(group)
                // 空工具箱：任务走单轮 chat 分支，不需要 LLM 调工具
                .toolkits(Set.of())
                .build();
    }

    /** 装配一个可运行的公司：只登记角色，worker 由各测试按需启动。 */
    private Company company(RoleSpec... specs) {
        AppConfig config = new AppConfig(
                new AppConfig.Llm("openai", "test-model", "k", "http://localhost:1",
                        new AppConfig.Llm.Retry(1, 0.01, 5)),
                new AppConfig.Schedule(1.0, 8, 18, 60_000L, 1.0, 600_000L),
                new AppConfig.Storage(dataDir.toString()),
                new AppConfig.Web("127.0.0.1", 0, 60_000L),
                new AppConfig.Mail("agentsoftware.local",
                        new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                new AppConfig.Toolkits(Set.of()));
        Company company = new CompanyBuilder(config, AppPaths.resolve(config.storage()),
                new JacksonJsonCodec())
                .withLlm(new FakeLlm())
                .withClientChannel(new ConsoleClientChannel())
                .withTranscript(new ChatFeed())
                .build();
        for (RoleSpec spec : specs) {
            company.staffing().hire(spec);
        }
        return company;
    }

    private static TalkMessage message(RoleId from, RoleId to, String text) {
        return new TalkMessage(from, from.value(), to, to.value(), "研发组", text, "NORMAL");
    }

    // ── 1) 同步等待：目标完成任务 → 结果回传发送方 ──────────────

    @Test
    void 同步等待收到目标完成任务的结果() throws InterruptedException {
        Company company = company(
                spec(A, "Wang Jianguo", "wangjianguo", "研发组"),
                spec(B, "Guo Xiaodong", "guoxiaodong", "研发组"));
        Team team = company.team();
        Agent sender = team.agent(A).orElseThrow();
        Agent target = team.agent(B).orElseThrow();
        target.start();
        try {
            TalkService talk = new TalkService(team, new ChatFeed());
            AtomicReference<Optional<String>> received = new AtomicReference<>();
            Thread caller = new Thread(() -> received.set(
                    talk.sendAndWait(message(A, B, "What's the progress?"), null, Duration.ofSeconds(10))));
            caller.start();

            await("发送方进入 WAITING", 5_000, () -> sender.state() == AgentState.WAITING);
            caller.join(5_000);
            assertFalse(caller.isAlive(), "等待应被目标完成的任务唤醒");

            assertTrue(received.get() != null && received.get().isPresent(), "应收到回复");
            assertTrue(received.get().get().contains("80% 完成"), received.get().get());
            assertEquals(AgentState.ON_DUTY_IDLE, sender.state(), "等待结束后状态应恢复");

            // 委派任务确实投给了目标，并带着 waiting 标记
            List<Task> history = target.history(0);
            Task delegated = history.stream()
                    .filter(t -> A.value().equals(t.context().stringOr("from", "")))
                    .findFirst().orElseThrow(() -> new AssertionError("目标历史里没有委派任务：" + history));
            assertTrue(delegated.context().boolOr("waiting", false), "委派任务应带 waiting=true");
            assertEquals("What's the progress?", delegated.context().stringOr("text", ""));
            assertTrue(delegated.description().contains("[talk]"), delegated.description());
        } finally {
            company.team().stopAll();
        }
    }

    // ── 2) 超时返回空 ─────────────────────────────────────────

    @Test
    void 超时返回空且不进入永久等待() {
        Company company = company(
                spec(A, "Wang Jianguo", "wangjianguo", "研发组"),
                spec(B, "Guo Xiaodong", "guoxiaodong", "研发组"));
        Team team = company.team();
        Agent sender = team.agent(A).orElseThrow();
        // 目标存在但 worker 未启动：任务只会排队，不会完成
        TalkService talk = new TalkService(team, new ChatFeed());

        long start = System.nanoTime();
        Optional<String> reply = talk.sendAndWait(message(A, B, "在吗？"), null, Duration.ofMillis(200));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(reply.isEmpty(), "超时应返回空");
        assertTrue(elapsedMillis >= 150, "应至少等待到超时，实际 " + elapsedMillis + "ms");
        assertEquals(AgentState.ON_DUTY_IDLE, sender.state(), "超时后状态应恢复");
        assertEquals(1, team.agent(B).orElseThrow().queueDepth(), "任务应仍在目标队列里");
    }

    // ── 3) 目标 / 发送方不存在返回 empty ───────────────────────

    @Test
    void 目标不存在返回空() {
        Company company = company(spec(A, "Wang Jianguo", "wangjianguo", "研发组"));
        Team team = company.team();
        TalkService talk = new TalkService(team, new ChatFeed());

        assertTrue(talk.sendAndWait(message(A, new RoleId("ghost"), "在吗"), null,
                Duration.ofMillis(50)).isEmpty());
        assertTrue(talk.findByName("不存在的人").isEmpty());
        assertTrue(talk.findByName("   ").isEmpty());
        assertTrue(talk.findByName(null).isEmpty());

        // 普通消息投给不存在的角色：只警告，不抛异常
        talk.send(message(A, new RoleId("ghost"), "在吗"));
        assertEquals(1, talk.roster().size());
    }

    // ── 4) roster / findByName ────────────────────────────────

    @Test
    void roster与按姓名或用户名查找() {
        Company company = company(
                spec(A, "Wang Jianguo", "wangjianguo", "研发组"),
                spec(B, "Guo Xiaodong", "guoxiaodong", "测试组"));
        TalkService talk = new TalkService(company.team(), new ChatFeed());

        assertEquals(2, talk.roster().size());
        assertEquals(A, talk.findByName("Wang Jianguo").orElseThrow().id());
        assertEquals(B, talk.findByName("Guo Xiaodong").orElseThrow().id());
        // 用户名大小写不敏感
        assertEquals(B, talk.findByName("GUOXIAODONG").orElseThrow().id());
        assertTrue(talk.roster().stream().anyMatch(s -> s.id().equals(A)));
    }

    // ── 5) 系统解阻塞（班次结束）唤醒无限等待 ─────────────────

    @Test
    void 系统解阻塞唤醒无限等待() throws InterruptedException {
        Company company = company(
                spec(A, "Wang Jianguo", "wangjianguo", "研发组"),
                spec(B, "Guo Xiaodong", "guoxiaodong", "研发组"));
        Team team = company.team();
        Agent sender = team.agent(A).orElseThrow();
        TalkService talk = new TalkService(team, new ChatFeed());

        AtomicReference<Optional<String>> received = new AtomicReference<>();
        // timeout=null：一直等，只能由系统 abort 唤醒
        Thread caller = new Thread(() -> received.set(
                talk.sendAndWait(message(A, B, "How's the progress?"), null, null)));
        caller.start();
        try {
            await("发送方进入 WAITING", 5_000, () -> sender.state() == AgentState.WAITING);
            sender.waits().abort("[System: the shift ended — treat this as the reply and wrap up.]");
            caller.join(5_000);
        } finally {
            company.team().stopAll();
        }

        assertFalse(caller.isAlive());
        assertTrue(received.get() != null && received.get().isPresent());
        assertTrue(received.get().get().contains("[System: the shift ended"), received.get().get());
        assertEquals(AgentState.ON_DUTY_IDLE, sender.state(), "解阻塞后状态应恢复");
        assertFalse(sender.waits().hasReply(), "end() 应清空回复信箱");
    }

    // ── 6) 普通消息（wait=false）进入目标队列并写轨迹 ──────────

    @Test
    void 普通消息投递到目标队列并写轨迹() {
        Company company = company(
                spec(A, "Wang Jianguo", "wangjianguo", "研发组"),
                spec(B, "Guo Xiaodong", "guoxiaodong", "研发组"));
        Team team = company.team();
        Agent target = team.agent(B).orElseThrow();
        ChatFeed feed = new ChatFeed();
        TalkService talk = new TalkService(team, feed);

        talk.send(message(A, B, "组件重构完成了"));

        assertEquals(1, target.queueDepth());
        Task task = target.pendingTasks().get(0);
        assertFalse(task.context().boolOr("waiting", true), "普通消息不应带 waiting 标记");
        assertEquals("组件重构完成了", task.context().stringOr("text", ""));
        assertEquals(A.value(), task.context().stringOr("from", ""));

        assertTrue(feed.since(0).stream().anyMatch(e -> ChatFeed.KIND_TALK.equals(e.kind())),
                "talk 轨迹应写入 feed");
    }
}
