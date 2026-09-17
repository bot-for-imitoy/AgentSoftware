package com.agent.software.tool.client;

import com.agent.software.agent.Agent;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.bootstrap.CompanyBuilder;
import com.agent.software.company.Company;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient;
import com.agent.software.tool.client.ClientChannel.ClientReply;
import com.agent.software.llm.ToolCallRequest;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.transcript.ChatFeed;
import com.agent.software.transcript.Transcript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClientToolkit} 的 talk_to_client 测试
 * （迁移自 master {@code tools/toolkits/client/ClientToolkitTest}）。
 *
 * <p>全局互斥（同一时刻只允许一位组长找客户）在新架构里落在 {@link WebClientChannel}
 * 的实例锁上，见 {@code WebClientChannelTest}。master 的 "只有 Leadership Group 被装配
 * talk_to_client" 规则移到了 {@code bootstrap.RoleTemplates}（包级可见，测试用不了），
 * 这里改为验证"按 {@code RoleSpec.toolkits} 选包"这条装配机制。
 */
class ClientToolkitTest {

    @TempDir
    Path dataDir;

    /** 可编程的假客户通道。 */
    private static final class FakeChannel implements ClientChannel {

        ClientReply reply = ClientReply.of("（默认回复）");
        ClientQuestion lastQuestion;

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public ClientReply ask(ClientQuestion question, Duration timeout) {
            this.lastQuestion = question;
            return reply;
        }
    }

    private static Tool tool(ClientToolkit toolkit) {
        List<Tool> tools = toolkit.instantiate();
        assertEquals(1, tools.size());
        return tools.get(0);
    }

    private static final RoleId CEO = new RoleId("CEO");

    @Test
    void 工具声明与参数schema() {
        ClientToolkit toolkit = new ClientToolkit(new FakeChannel(), new ChatFeed());
        assertEquals("client", toolkit.id());
        Tool talk = tool(toolkit);
        assertEquals("talk_to_client", talk.spec().name());

        var schema = talk.spec().schema().toMap();
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("message"), schema.get("required"));
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) schema.get("properties");
        assertEquals("string", ((java.util.Map<?, ?>) props.get("message")).get("type"));
    }

    @Test
    void 缺少message参数返回错误() {
        ClientToolkit toolkit = new ClientToolkit(new FakeChannel(), new ChatFeed());
        ToolResult result = tool(toolkit).invoke(CEO, Payload.empty());
        assertTrue(result.error());
        assertTrue(result.text().contains("message"), result.text());
    }

    @Test
    void 通道不可用时返回可读错误() {
        FakeChannel channel = new FakeChannel();
        channel.reply = ClientReply.unavailable("控制台不可交互");
        ClientToolkit toolkit = new ClientToolkit(channel, new ChatFeed());

        ToolResult result = tool(toolkit).invoke(CEO, Payload.of("message", "你好"));
        assertTrue(result.error(), result.text());
        assertTrue(result.text().contains("控制台不可交互"), result.text());
    }

    @Test
    void 成功时返回客户回复并写进轨迹() {
        FakeChannel channel = new FakeChannel();
        channel.reply = ClientReply.of("请帮我做一个支付系统");
        ChatFeed feed = new ChatFeed();
        ClientToolkit toolkit = new ClientToolkit(channel, feed);

        ToolResult result = tool(toolkit).invoke(CEO, Payload.of("message", "请问需求是什么？"));
        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("请帮我做一个支付系统"), result.text());

        // 提问写进 Transcript.client(...)
        List<Transcript.Entry> entries = feed.since(0);
        assertEquals(1, entries.size());
        Transcript.Entry question = entries.get(0);
        assertEquals(ChatFeed.KIND_CLIENT, question.kind());
        assertEquals("请问需求是什么？", question.text());
        assertEquals("CEO", question.fromRoleId());
        assertEquals(ChatFeed.CLIENT_NAME, question.toName());
        assertEquals("请问需求是什么？", channel.lastQuestion.text());
    }

    @Test
    void 空回复返回可读提示() {
        FakeChannel channel = new FakeChannel();
        channel.reply = ClientReply.of("   ");
        ClientToolkit toolkit = new ClientToolkit(channel, new ChatFeed());

        ToolResult result = tool(toolkit).invoke(CEO, Payload.of("message", "在吗"));
        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("空回复"), result.text());
    }

    @Test
    void 按角色toolkits装配出talk_to_client() {
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

        Agent leader = company.staffing().hire(RoleSpec.builder()
                .id(new RoleId("CEO")).name("Lin Zong").username("linzong")
                .group("Leadership Group").toolkits(Set.of("time", "client")).build());
        Agent dev = company.staffing().hire(RoleSpec.builder()
                .id(new RoleId("frontend_dev_1")).name("Gu Chengyu").username("guchengyu")
                .group("Frontend Development Group").toolkits(Set.of("time")).build());
        leader.start();
        dev.start();
        try {
            assertTrue(hasTool(leader, "talk_to_client"), "选了 client 工具包的角色应装配 talk_to_client");
            assertFalse(hasTool(dev, "talk_to_client"), "没选 client 工具包的角色不应有 talk_to_client");
            assertTrue(leader.toolbox().specs().stream().anyMatch(s -> "get_time".equals(s.name())),
                    "同一角色的其他工具包应正常装配");
        } finally {
            company.team().stopAll();
        }
    }

    private static boolean hasTool(Agent agent, String name) {
        return agent.toolbox() != null
                && agent.toolbox().specs().stream().anyMatch(s -> name.equals(s.name()));
    }

    private static final class FakeLlm implements LlmClient {
        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("好的。", null, 3);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            return new ToolReply("好的。", null, List.of(), 4);
        }

        @Override
        public ChatReply summarize(String text, double temperature, int maxTokens) {
            return new ChatReply("（摘要）", null, 2);
        }
    }
}
