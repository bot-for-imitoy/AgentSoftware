package com.agent.software.company;

import com.agent.software.agent.Agent;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.bootstrap.CompanyBuilder;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.Ids.MailId;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.sim.event.EventKind;
import com.agent.software.tool.mail.FileMailbox;
import com.agent.software.tool.mail.Mailbox;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.transcript.ChatFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件落信 → {@code NEW_MAIL} 定向事件 → 收件人任务。
 *
 * <p>master 对应 {@code AgentSystemMailNotifyTest}：邮箱落信后收件人拿到一条
 * "去读邮件"的任务；To + CC 各通知一次；外部地址不产生事件；自寄不通知自己；
 * 下班 / 收尾中的收件人按投递策略处理。
 *
 * <p>与 master 的两处刻意差异（都对齐新架构的显式取舍）：
 * <ul>
 *   <li>master 对下班收件人直接丢弃非紧急定向事件（queueDepth 0 + journal "skipped"）；
 *       新架构的 {@code DefaultDeliveryPolicy} 改成 HOLD（进暂存队列，次日提升）；</li>
 *   <li>任务描述是通用的 "[email/NEW_MAIL] 主题"，不再内嵌发件人姓名与 read_mail 提示
 *       （工具指引由 System Prompt 承担）。</li>
 * </ul>
 */
class CompanyMailNotifyTest {

    @TempDir
    Path dataDir;

    private static final class NoopLlm implements LlmClient {

        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("好的。", null, 1);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            return new ToolReply("好的。", null, List.of(), 1);
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
                new AppConfig.Toolkits(Set.of("email", "note", "task_view", "time")));
    }

    private Company company() {
        AppConfig config = config(dataDir);
        return new CompanyBuilder(config, AppPaths.resolve(config.storage()), new JacksonJsonCodec())
                .withLlm(new NoopLlm())
                .withTranscript(new ChatFeed())
                .build();
    }

    private static RoleSpec spec(String id, String name, String group) {
        return RoleSpec.builder()
                .id(new RoleId(id))
                .name(name)
                .username(id.toLowerCase(java.util.Locale.ROOT))
                .title(group + " 成员")
                .group(group)
                .skills(List.of("沟通"))
                .interestKeywords(Set.of("mail"))
                .toolkits(Set.of("email", "note", "task_view", "time"))
                .build();
    }

    private static Agent hire(Company company, RoleSpec spec) {
        company.staffing().hire(spec);
        return company.team().agent(spec.id()).orElseThrow();
    }

    /** 启动一次以装配工具箱，再停掉 worker —— 让队列在断言期间保持确定性（不起 LLM）。 */
    private static Agent freeze(Agent agent) {
        agent.start();
        agent.stop();
        return agent;
    }

    private static ToolResult send(Agent from, String to, String cc, String subject, String body) {
        Payload args = Payload.of("to", to).with("subject", subject).with("body", body);
        if (cc != null) {
            args = args.with("cc", cc);
        }
        return from.toolbox().invoke("send_email", args);
    }

    private static RoleSpec ceoSpec() {
        return spec("CEO", "Lin Zong", "Leadership Group");
    }

    private static RoleSpec ctoSpec() {
        return spec("CTO", "Gao Yuan", "Leadership Group");
    }

    private static RoleSpec cfoSpec() {
        return spec("CFO", "Qian Cai", "Leadership Group");
    }

    // ── 1. 在岗收件人收到 NEW_MAIL 任务 ────────────────────────

    @Test
    void 在岗收件人收到NEW_MAIL任务() {
        Company company = company();
        try {
            Agent ceo = freeze(hire(company, ceoSpec()));
            Agent cto = freeze(hire(company, ctoSpec()));

            ToolResult sent = send(ceo, "Gao Yuan", null, "Architecture sync", "Please review the plan");
            assertFalse(sent.error(), sent.text());

            assertEquals(1, cto.mailbox().readyDepth(), "收件人应收到一条 NEW_MAIL 任务");
            assertEquals(0, ceo.mailbox().readyDepth(), "发件人不应被通知");

            Task task = cto.mailbox().peek().orElseThrow();
            assertEquals(EventKind.NEW_MAIL, task.source());
            assertTrue(task.description().contains("Architecture sync"), task.description());
            assertEquals("CTO", task.context().stringOr("recipient", ""));
            assertTrue(task.context().string("mail_id").isPresent());
            assertEquals("ceo@agentsoftware.local", task.context().stringOr("from", ""));

            // 邮件本身已在收件箱里且未读
            ToolResult inbox = cto.toolbox().invoke("read_mail", Payload.of("limit", 0));
            assertFalse(inbox.error(), inbox.text());
            assertTrue(inbox.text().contains("Architecture sync"), inbox.text());
        } finally {
            company.stop();
        }
    }

    // ── 2. To + CC 各通知一次 ──────────────────────────────────

    @Test
    void to与cc收件人都会被通知() {
        Company company = company();
        try {
            Agent ceo = freeze(hire(company, ceoSpec()));
            Agent cto = freeze(hire(company, ctoSpec()));
            Agent cfo = freeze(hire(company, cfoSpec()));

            ToolResult sent = send(ceo, "Gao Yuan", "Qian Cai", "Budget review", "Please check the numbers.");
            assertFalse(sent.error(), sent.text());

            assertEquals(1, cto.mailbox().readyDepth());
            assertEquals(1, cfo.mailbox().readyDepth());
            assertEquals(0, ceo.mailbox().readyDepth());
            assertEquals(EventKind.NEW_MAIL, cto.mailbox().peek().orElseThrow().source());
            assertEquals(EventKind.NEW_MAIL, cfo.mailbox().peek().orElseThrow().source());
        } finally {
            company.stop();
        }
    }

    // ── 3. 下班收件人按投递策略处理（HOLD 暂存） ────────────────

    @Test
    void 下班收件人的通知进入暂存队列() {
        Company company = company();
        try {
            Agent ceo = freeze(hire(company, ceoSpec()));
            Agent cto = freeze(hire(company, ctoSpec()));
            cto.stateMachine().toOffDuty();

            ToolResult sent = send(ceo, "Gao Yuan", null, "Evening note", "Nothing urgent.");
            assertFalse(sent.error(), sent.text());

            // 邮件照常落信
            assertTrue(Files.exists(dataDir.resolve("mail").resolve("cto_at_agentsoftware.local.json")));
            // 但下班角色不被打扰：非紧急定向事件进暂存队列而不是可执行队列
            assertEquals(0, cto.mailbox().readyDepth(), "下班角色不应立刻执行");
            assertEquals(1, cto.mailbox().deferredDepth(), "应进入暂存队列");

            // 上班时提升为可执行
            cto.promoteDeferred();
            assertEquals(1, cto.mailbox().readyDepth());
            assertEquals(0, cto.mailbox().deferredDepth());
            assertEquals(EventKind.NEW_MAIL, cto.mailbox().peek().orElseThrow().source());
        } finally {
            company.stop();
        }
    }

    // ── 4. 外部地址不产生事件 ──────────────────────────────────

    @Test
    void 外部收件人不产生事件() {
        Company company = company();
        try {
            Agent ceo = freeze(hire(company, ceoSpec()));
            Agent cto = freeze(hire(company, ctoSpec()));
            Agent cfo = freeze(hire(company, cfoSpec()));

            ToolResult sent = send(ceo, "external@partner-corp.example", null, "Hi", "Are you free?");
            assertFalse(sent.error(), sent.text());

            assertEquals(0, ceo.queueDepth());
            assertEquals(0, cto.queueDepth());
            assertEquals(0, cfo.queueDepth());
        } finally {
            company.stop();
        }
    }

    // ── 5. 自寄邮件不通知自己 ──────────────────────────────────

    @Test
    void 自寄邮件只落信不通知自己() {
        Company company = company();
        try {
            Agent cto = freeze(hire(company, ctoSpec()));

            ToolResult sent = send(cto, "Gao Yuan", null, "Reminder to self", "Buy coffee beans.");
            assertFalse(sent.error(), sent.text());

            assertEquals(0, cto.queueDepth(), "不应给自己产生 NEW_MAIL 任务");
            assertTrue(Files.exists(dataDir.resolve("mail").resolve("cto_at_agentsoftware.local.json")),
                    "邮件本身仍应落信");
        } finally {
            company.stop();
        }
    }

    // ── 6. 独立邮箱实例没有通知接线 ─────────────────────────────

    @Test
    void 独立邮箱实例静默投递() {
        AppPaths paths = AppPaths.resolve(new AppConfig.Storage(dataDir.toString()));
        FileMailbox mailbox = new FileMailbox(
                new AppConfig.Mail("agentsoftware.local", new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                paths);

        MailId id = mailbox.send(new Mailbox.OutgoingMail("a@agentsoftware.local", "A",
                List.of("b@agentsoftware.local"), List.of(), "S", "B"));

        assertTrue(id != null && !id.value().isBlank());
        assertEquals(1, mailbox.inbox("b@agentsoftware.local", 0).size());
        assertEquals(1, mailbox.unreadCount("b@agentsoftware.local"));
    }
}
