package com.agent.software.company;

import com.agent.software.agent.Agent;
import com.agent.software.agent.AgentState;
import com.agent.software.agent.role.RoleSpec;
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
import com.agent.software.agent.task.Task;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.transcript.ChatFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多个 {@link Company} 实例之间的隔离。
 *
 * <p>master 对应 {@code AgentSystemIsolationTest}：改造前 {@code ComputerManager} /
 * {@code MailService} / {@code ClientCommunicationLock} 等是进程级单例，全局数据路径
 * 让"一个进程只能安全跑一个 AgentSystem"；新架构把所有协作者都做成实例（每个 Company
 * 一套 Team/时钟/路由/工具/邮箱），数据目录由 {@link AppPaths} 注入。本测试验证这组保证。
 *
 * <p>master 的两个用例（默认系统沿用 {@code ./data} 布局、独立角色回退进程默认单例）
 * 在新架构里没有对应物：不再有进程级单例，也不再保留"默认数据目录 = ./data"的旧布局。
 */
class CompanyIsolationTest {

    @TempDir
    Path tmp;

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
                new AppConfig.Toolkits(Set.of("memory", "note", "task_view", "time", "email")));
    }

    private Company company(Path dir) {
        AppConfig config = config(dir);
        return new CompanyBuilder(config, AppPaths.resolve(config.storage()), new JacksonJsonCodec())
                .withLlm(new NoopLlm())
                .withTranscript(new ChatFeed())
                .build();
    }

    private static RoleSpec spec(String id, String name) {
        return RoleSpec.builder()
                .id(new RoleId(id))
                .name(name)
                .username(id)
                .title("工程师")
                .group("研发组")
                .skills(List.of("Java"))
                .interestKeywords(Set.of("bug"))
                .toolkits(Set.of("memory", "note", "task_view", "time", "email"))
                .build();
    }

    private static Set<String> ids(Company company) {
        Set<String> out = new TreeSet<>();
        for (Agent agent : company.team().agents()) {
            out.add(agent.id().value());
        }
        return out;
    }

    private static Task task(String description) {
        return new Task(TaskId.generate(), Priority.NORMAL, description,
                new EventKind("test", "TASK"), Payload.empty(), Instant.now(), null);
    }

    // ── 1. 协作者与角色对象完全独立 ─────────────────────────────

    @Test
    void 两个公司的协作者与角色互相独立() {
        Company a = company(tmp.resolve("a"));
        Company b = company(tmp.resolve("b"));
        try {
            // 每个公司持有自己的协作者
            assertNotSame(a.team(), b.team());
            assertNotSame(a.clock(), b.clock());
            assertNotSame(a.router(), b.router());
            assertNotSame(a.schedule(), b.schedule());
            assertNotSame(a.staffing(), b.staffing());
            assertNotSame(a.driver(), b.driver());
            assertNotSame(a.director(), b.director());

            // 同一个 role_id 在各自公司里是不同对象
            a.staffing().hire(spec("alpha", "甲"));
            b.staffing().hire(spec("alpha", "甲"));
            Agent aa = a.team().agent(new RoleId("alpha")).orElseThrow();
            Agent ba = b.team().agent(new RoleId("alpha")).orElseThrow();
            assertNotSame(aa, ba);

            // 状态互不影响
            aa.stateMachine().toOffDuty();
            assertEquals(AgentState.OFF_DUTY, aa.state());
            assertEquals(AgentState.ON_DUTY_IDLE, ba.state());

            // 队列互不影响
            aa.submit(task("A 的任务"), false);
            assertEquals(1, aa.mailbox().readyDepth());
            assertEquals(0, ba.mailbox().readyDepth());

            // 时钟互不影响（SimClock 的推进只影响本实例）
            a.clock().advanceTicks(100);
            assertEquals(100L, a.clock().now().value());
            assertEquals(0L, b.clock().now().value());

            // 暂停开关互不影响
            a.pause("A 暂停");
            assertTrue(a.paused());
            assertFalse(b.paused());
            assertEquals("A 暂停", a.pauseReason());
            assertEquals("", b.pauseReason());
            a.resume();
            assertFalse(a.paused());
        } finally {
            a.stop();
            b.stop();
        }
    }

    // ── 2. 工具与磁盘目录互相隔离 ───────────────────────────────

    @Test
    void 工具与磁盘目录互相隔离() {
        Path dirA = tmp.resolve("a");
        Path dirB = tmp.resolve("b");
        Company a = company(dirA);
        Company b = company(dirB);
        try {
            a.staffing().hire(spec("alpha", "甲"));
            b.staffing().hire(spec("alpha", "甲"));
            Agent aa = a.team().agent(new RoleId("alpha")).orElseThrow();
            Agent ba = b.team().agent(new RoleId("alpha")).orElseThrow();

            // 启动一次以装配工具箱，再停掉 worker 让队列保持确定性
            aa.start();
            ba.start();
            aa.stop();
            ba.stop();
            assertNotSame(aa.toolbox(), ba.toolbox());

            // 笔记只落到本公司的数据目录
            ToolResult written = aa.toolbox().invoke("write_note",
                    Payload.of("title", "A 的笔记").with("content", "只有 A 能看到"));
            assertFalse(written.error(), written.text());
            assertTrue(Files.exists(dirA.resolve("notes").resolve("alpha.json")));
            assertFalse(Files.exists(dirB.resolve("notes").resolve("alpha.json")));
            assertFalse(ba.toolbox().invoke("list_notes", Payload.empty()).text().contains("A 的笔记"));

            // 邮件：A 内部发信只落到 A 的邮箱目录，并只通知 A 的收件人
            a.staffing().hire(spec("beta", "乙"));
            Agent abeta = a.team().agent(new RoleId("beta")).orElseThrow();
            abeta.start();
            abeta.stop();

            ToolResult sent = aa.toolbox().invoke("send_email",
                    Payload.of("to", "乙").with("subject", "架构同步").with("body", "请评审方案"));
            assertFalse(sent.error(), sent.text());
            assertTrue(Files.exists(dirA.resolve("mail").resolve("beta_at_agentsoftware.local.json")));
            assertFalse(Files.exists(dirB.resolve("mail").resolve("beta_at_agentsoftware.local.json")));
            assertEquals(1, abeta.mailbox().readyDepth(), "A 的收件人应收到 NEW_MAIL 任务");
            assertEquals(0, ba.mailbox().readyDepth(), "B 的同名角色不应被打扰");
        } finally {
            a.stop();
            b.stop();
        }
    }

    // ── 3. 读档只恢复自己的角色 ─────────────────────────────────

    @Test
    void 读档只恢复自己的角色() {
        Path dirA = tmp.resolve("a");
        Path dirB = tmp.resolve("b");

        Company a = company(dirA);
        try {
            a.staffing().hire(spec("alpha", "甲"));
            assertEquals(Set.of("alpha"), ids(a));
            a.save();

            // B 指向另一个目录：读不到 A 的存档
            Company b = company(dirB);
            try {
                b.staffing().hire(spec("beta", "乙"));
                assertEquals(0, b.restore(), "另一个数据目录里没有存档");
                assertEquals(Set.of("beta"), ids(b), "读档失败不应改动已有花名册");
            } finally {
                b.stop();
            }

            // C 指向 A 的数据目录：只恢复 A 的角色
            Company c = company(dirA);
            try {
                assertEquals(1, c.restore());
                assertEquals(Set.of("alpha"), ids(c));
                assertFalse(ids(c).contains("beta"), "不应恢复别的公司的角色");
                Agent restored = c.team().agent(new RoleId("alpha")).orElseThrow();
                assertEquals("甲", restored.spec().name());
            } finally {
                c.stop();
            }
        } finally {
            a.stop();
        }
    }
}
