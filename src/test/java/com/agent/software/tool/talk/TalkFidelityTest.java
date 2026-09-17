package com.agent.software.tool.talk;

import com.agent.software.agent.Agent;
import com.agent.software.agent.Team;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.bootstrap.CompanyBuilder;
import com.agent.software.company.Company;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.tool.client.ConsoleClientChannel;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * talk 的两条"保真性"行为（master 有、实现时差点漏掉）：
 *
 * <ol>
 *   <li><b>互相等待要拆环</b>：A 等 B 时 B 又等 A，两边都会永远等下去（只能等下班被 abort）。
 *       master 会检测并给合成回复；</li>
 *   <li><b>云盘附件</b>：System Prompt 明确告诉模型"可以用 talk 的 attachment 参数传云盘路径"，
 *       工具就必须真的声明并校验这个参数（只允许相对路径，拒绝绝对路径与 {@code ..}）。</li>
 * </ol>
 */
class TalkFidelityTest {

    @TempDir
    Path dataDir;

    private static final RoleId A = new RoleId("architect");
    private static final RoleId B = new RoleId("reviewer");

    private static final class FakeLlm implements LlmClient {
        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("收到。", null, 5);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            return new ToolReply("收到。", null, List.of(), 6);
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            return new ChatReply("（摘要）", null, 3);
        }
    }

    private static RoleSpec spec(RoleId id, String name, String username) {
        return RoleSpec.builder()
                .id(id).name(name).username(username).group("研发组")
                .toolkits(Set.of("talk"))
                .build();
    }

    private Company company() {
        AppConfig config = new AppConfig(
                new AppConfig.Llm("openai", "test-model", "k", "http://localhost:1",
                        new AppConfig.Llm.Retry(1, 0.01, 5)),
                new AppConfig.Schedule(1.0, 8, 18, 60_000L, 1.0, 600_000L),
                new AppConfig.Storage(dataDir.toString()),
                new AppConfig.Web("127.0.0.1", 0, 60_000L),
                new AppConfig.Mail("agentsoftware.local",
                        new AppConfig.Mail.Smtp("", 587, "", "", "", true)),
                new AppConfig.Toolkits(Set.of("talk")));
        Company company = new CompanyBuilder(config, AppPaths.resolve(config.storage()),
                new JacksonJsonCodec())
                .withLlm(new FakeLlm())
                .withClientChannel(new ConsoleClientChannel())
                .withTranscript(new ChatFeed())
                .build();
        company.staffing().hire(spec(A, "Wang Jianguo", "wangjianguo"));
        company.staffing().hire(spec(B, "Zhang Wei", "zhangwei"));
        return company;
    }

    // ── 1) 互相等待拆环 ────────────────────────────────────────

    @Test
    void 互相等待时拆解等待环而不是一起卡死() throws InterruptedException {
        Company company = company();
        Team team = company.team();
        Agent a = team.agent(A).orElseThrow();
        Agent b = team.agent(B).orElseThrow();

        TalkService talk = new TalkService(team, new ChatFeed());

        // B 先"正在等 A"（模拟真实的互相等待局面）
        b.waits().begin(A);

        AtomicReference<Optional<String>> reply = new AtomicReference<>();
        Thread caller = new Thread(() -> reply.set(talk.sendAndWait(
                new TalkMessage(A, "Wang Jianguo", B, "Zhang Wei", "研发组", "帮我看看", "NORMAL"),
                null, Duration.ofSeconds(5))));
        caller.setDaemon(true);
        caller.start();
        caller.join(3_000);

        assertFalse(caller.isAlive(), "互相等待必须立刻被拆解，不能真的阻塞到超时");
        assertTrue(reply.get() != null && reply.get().isPresent(), "应当拿到合成回复");
        assertTrue(reply.get().orElse("").contains("也在等你的回复"), reply.get().orElse(""));
        assertTrue(a.mailbox().readyDepth() + a.mailbox().deferredDepth() >= 0);
        assertTrue(b.mailbox().readyDepth() >= 1, "消息仍应照常送达对方队列");

        b.waits().end();
        company.stop();
    }

    // ── 2) 云盘附件参数 ────────────────────────────────────────

    @Test
    void 附件参数只接受云盘相对路径() {
        Company company = company();
        Agent a = company.team().agent(A).orElseThrow();
        a.start();

        // 工具包里的 talk 工具通过公开的 invoke 入口验证：非法路径必须在投递前被拦下
        TalkToolkit toolkit = new TalkToolkit(new TalkService(company.team(), new ChatFeed()),
                company.team(), new ChatFeed());
        Tool talk = toolkit.instantiate().get(0);

        ToolResult absolute = talk.invoke(A, Payload.of("person", "Zhang Wei")
                .with("message", "看下这个").with("attachment", "/etc/passwd"));
        assertTrue(absolute.error(), absolute.text());
        assertTrue(absolute.text().contains("相对"), absolute.text());

        ToolResult traversal = talk.invoke(A, Payload.of("person", "Zhang Wei")
                .with("message", "看下这个").with("attachment", "Public/../../etc/passwd"));
        assertTrue(traversal.error(), traversal.text());
        assertTrue(traversal.text().contains(".."), traversal.text());

        ToolResult trailing = talk.invoke(A, Payload.of("person", "Zhang Wei")
                .with("message", "看下这个").with("attachment", "Public/"));
        assertTrue(trailing.error(), trailing.text());

        ToolResult ok = talk.invoke(A, Payload.of("person", "Zhang Wei")
                .with("message", "看下这个").with("attachment", "Public/proposal.md"));
        assertFalse(ok.error(), ok.text());

        // schema 里必须真的声明 attachment，否则模型根本不会用它
        assertTrue(talk.spec().schema().toMap().toString().contains("attachment"));

        company.stop();
    }
}
