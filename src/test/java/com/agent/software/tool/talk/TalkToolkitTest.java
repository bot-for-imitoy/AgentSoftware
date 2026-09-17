package com.agent.software.tool.talk;

import com.agent.software.agent.Agent;
import com.agent.software.agent.AgentState;
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
import com.agent.software.llm.ToolCallRequest;
import com.agent.software.tool.client.ConsoleClientChannel;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.transcript.ChatFeed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TalkToolkit} 组内沟通限制与 list_roles 测试
 * （迁移自 master {@code tools/TalkGroupTest}）。
 *
 * <p>组内规则沿用 master：双方都明确有组且组名不同才拒绝，并提示改用 send_email；
 * 无组的新人不受限制。这里用真实的 {@code Team} 花名册。
 */
class TalkToolkitTest {

    @TempDir
    Path dataDir;

    private static final RoleId FRONTEND_DEV = new RoleId("frontend_dev_1");
    private static final RoleId FRONTEND_LEAD = new RoleId("frontend_lead");
    private static final RoleId TESTER = new RoleId("tester_1");
    private static final RoleId ARCHITECT = new RoleId("architect");
    private static final RoleId NEWBIE = new RoleId("newbie_1");

    private static final String FRONTEND_GROUP = "Frontend Development Group";
    private static final String TESTING_GROUP = "Testing Group";
    private static final String ARCH_GROUP = "Architecture & Release Group";

    private static final class FakeLlm implements LlmClient {

        @Override
        public ChatReply chat(ChatRequest request) {
            return new ChatReply("80% 完成。", null, 5);
        }

        @Override
        public ToolReply chatWithTools(ToolChatRequest request) {
            return new ToolReply("80% 完成。", null, List.of(), 6);
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

    private static RoleSpec role(RoleId id, String name, String username, String group, String resp) {
        return RoleSpec.builder()
                .id(id).name(name).username(username).group(group)
                .responsibilities(resp).toolkits(Set.of())
                .build();
    }

    private static final RoleSpec[] ROSTER = {
            role(FRONTEND_DEV, "顾承宇", "guchengyu", FRONTEND_GROUP, "前端开发"),
            role(FRONTEND_LEAD, "陈思远", "chensiyuan", FRONTEND_GROUP, "前端组长"),
            role(TESTER, "郭晓东", "guoxiaodong", TESTING_GROUP, "测试"),
            role(ARCHITECT, "王建国", "wangjianguo", ARCH_GROUP, "架构"),
            role(NEWBIE, "新人", "newbie", "", "待分配"),
    };

    private Company company() {
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
        for (RoleSpec spec : ROSTER) {
            company.staffing().hire(spec);
        }
        return company;
    }

    private static TalkToolkit toolkit(Company company, ChatFeed feed) {
        Team team = company.team();
        return new TalkToolkit(new TalkService(team, feed), team, feed);
    }

    private static Tool tool(TalkToolkit toolkit, String name) {
        return toolkit.instantiate().stream()
                .filter(t -> name.equals(t.spec().name()))
                .findFirst()
                .orElseThrow();
    }

    private static ToolResult talk(TalkToolkit toolkit, RoleId sender, String person,
                                   String message, boolean wait) {
        return tool(toolkit, "talk").invoke(sender,
                Payload.of("person", person).with("message", message).with("wait", wait));
    }

    // ── 同组可 talk ────────────────────────────────────────────

    @Test
    void 同组消息投递到目标队列() {
        Company company = company();
        TalkToolkit toolkit = toolkit(company, new ChatFeed());

        ToolResult result = talk(toolkit, FRONTEND_DEV, "陈思远", "组件重构完成了", false);
        assertFalse(result.error(), result.text());
        assertTrue(result.text().contains("陈思远"), result.text());
        assertEquals(1, company.team().agent(FRONTEND_LEAD).orElseThrow().queueDepth());
    }

    @Test
    void 同组同步等待收到回复() throws InterruptedException {
        Company company = company();
        Agent sender = company.team().agent(FRONTEND_DEV).orElseThrow();
        Agent target = company.team().agent(FRONTEND_LEAD).orElseThrow();
        target.start();
        try {
            TalkToolkit toolkit = toolkit(company, new ChatFeed());
            AtomicReference<ToolResult> result = new AtomicReference<>();
            Thread caller = new Thread(() -> result.set(
                    talk(toolkit, FRONTEND_DEV, "陈思远", "进度怎么样？", true)));
            caller.start();

            await("发送方进入 WAITING", 5_000, () -> sender.state() == AgentState.WAITING);
            caller.join(5_000);
            assertFalse(caller.isAlive());
            assertFalse(result.get().error(), result.get().text());
            assertTrue(result.get().text().contains("收到 陈思远 的回复"), result.get().text());
            assertEquals(AgentState.ON_DUTY_IDLE, sender.state());
        } finally {
            company.team().stopAll();
        }
    }

    // ── 跨组被拒并提示用邮件 ───────────────────────────────────

    @Test
    void 跨组被拒并提示改用邮件() {
        Company company = company();
        TalkToolkit toolkit = toolkit(company, new ChatFeed());

        ToolResult result = talk(toolkit, TESTER, "王建国", "有个架构问题", false);
        assertTrue(result.error(), result.text());
        assertTrue(result.text().contains("只用于同组沟通"), result.text());
        assertTrue(result.text().contains(TESTING_GROUP), result.text());
        assertTrue(result.text().contains(ARCH_GROUP), result.text());
        assertTrue(result.text().contains("send_email"), result.text());
        assertEquals(0, company.team().agent(ARCHITECT).orElseThrow().queueDepth(), "跨组消息不应投递");
    }

    @Test
    void 跨组同步等待也被拒且不进入等待() {
        Company company = company();
        Agent sender = company.team().agent(TESTER).orElseThrow();
        TalkToolkit toolkit = toolkit(company, new ChatFeed());

        ToolResult result = talk(toolkit, TESTER, "王建国", "紧急事项", true);
        assertTrue(result.error());
        assertTrue(result.text().contains("只用于同组沟通"), result.text());
        assertEquals(AgentState.ON_DUTY_IDLE, sender.state(), "被拒后不应进入 WAITING");
    }

    @Test
    void 无组角色不受组内限制() {
        Company company = company();
        TalkToolkit toolkit = toolkit(company, new ChatFeed());

        ToolResult toMember = talk(toolkit, NEWBIE, "顾承宇", "你好", false);
        assertFalse(toMember.error(), toMember.text());
        ToolResult fromMember = talk(toolkit, FRONTEND_DEV, "新人", "欢迎", false);
        assertFalse(fromMember.error(), fromMember.text());
    }

    // ── 找不到成员 ─────────────────────────────────────────────

    @Test
    void 找不到成员时提示list_roles() {
        Company company = company();
        TalkToolkit toolkit = toolkit(company, new ChatFeed());

        ToolResult result = talk(toolkit, TESTER, "查无此人", "在吗", false);
        assertTrue(result.error());
        assertTrue(result.text().contains("list_roles"), result.text());
        assertTrue(talk(toolkit, TESTER, "郭晓东", "  ", false).error(), "空消息应报错");
        assertTrue(tool(toolkit, "talk").invoke(TESTER, Payload.of("message", "hi")).error(),
                "缺 person 应报错");
    }

    // ── list_roles 分组过滤 ────────────────────────────────────

    @Test
    void list_roles展示成员与组并支持group过滤() {
        Company company = company();
        TalkToolkit toolkit = toolkit(company, new ChatFeed());
        Tool listRoles = tool(toolkit, "list_roles");
        RoleId caller = FRONTEND_DEV;

        ToolResult all = listRoles.invoke(caller, Payload.empty());
        assertFalse(all.error(), all.text());
        assertTrue(all.text().contains("顾承宇"), all.text());
        assertTrue(all.text().contains("王建国"), all.text());
        assertTrue(all.text().contains(FRONTEND_GROUP), all.text());
        assertTrue(all.text().contains("未分组"), all.text());
        assertFalse(all.text().contains("frontend_dev_1"), "花名册不应暴露 role_id");

        ToolResult filtered = listRoles.invoke(caller, Payload.of("group", "frontend"));
        assertTrue(filtered.text().contains("顾承宇"), filtered.text());
        assertFalse(filtered.text().contains("王建国"), filtered.text());

        ToolResult none = listRoles.invoke(caller, Payload.of("group", "Nonexistent Group"));
        assertTrue(none.text().contains("没有匹配组"), none.text());
    }

    @Test
    void talk工具声明参数schema() {
        Company company = company();
        TalkToolkit toolkit = toolkit(company, new ChatFeed());
        assertEquals("talk", toolkit.id());
        assertEquals(List.of("talk", "list_roles"),
                toolkit.instantiate().stream().map(t -> t.spec().name()).toList());

        var schema = tool(toolkit, "talk").spec().schema().toMap();
        assertEquals("object", schema.get("type"));
        assertEquals(List.of("person", "message"), schema.get("required"));
        @SuppressWarnings("unchecked")
        var props = (java.util.Map<String, Object>) schema.get("properties");
        assertEquals("string", ((java.util.Map<?, ?>) props.get("person")).get("type"));
        assertEquals("boolean", ((java.util.Map<?, ?>) props.get("wait")).get("type"));
    }
}
