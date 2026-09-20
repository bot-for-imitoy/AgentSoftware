package com.agent.software.web;

import com.agent.software.agent.AgentSnapshot;
import com.agent.software.agent.AgentState;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.company.CompanyStatus;
import com.agent.software.company.CompanyView;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.DayTick;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.transcript.ChatFeed;
import com.agent.software.transcript.Transcript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatWebServer} 的 HTTP 端到端测试（port=0，真实 HttpClient）。
 *
 * <p>master 对应 {@code web.ChatWebServerTest}（当时直接绑 {@code AgentSystem}）。
 * 新架构只依赖 {@link CompanyView} + {@link Transcript.Feed}，因此这里用假视图驱动，
 * 断言字段形状与 master 前端 {@code app.js} 消费的完全一致。
 */
class ChatWebServerTest {

    private ChatFeed feed;
    private FakeView view;
    private ChatWebServer server;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JacksonJsonCodec json = new JacksonJsonCodec();

    @BeforeEach
    void setUp() throws IOException {
        feed = new ChatFeed();
        view = new FakeView(List.of(ceoSpec(), ctoSpec(), devSpec()));
        server = new ChatWebServer(view, feed, "127.0.0.1", 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ── 静态资源 ───────────────────────────────────────────────

    @Test
    void 静态资源首页与脚本() throws Exception {
        HttpResponse<String> index = get("/");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("AgentSoftware"));
        assertTrue(index.body().contains("emailBtn"));
        assertTrue(index.headers().firstValue("Content-Type").orElse("").contains("text/html"));

        HttpResponse<String> js = get("/app.js");
        assertEquals(200, js.statusCode());
        assertTrue(js.body().contains("pollState"));
        assertTrue(js.body().contains("/api/email"));
        assertTrue(js.headers().firstValue("Content-Type").orElse("").contains("javascript"));
    }

    @Test
    void 未知路径与未知api返回404() throws Exception {
        assertEquals(404, get("/nope.txt").statusCode());
        assertEquals(404, get("/api/unknown").statusCode());
    }

    // ── /api/state ────────────────────────────────────────────

    @Test
    void state接口字段形状() throws Exception {
        HttpResponse<String> response = get("/api/state");
        assertEquals(200, response.statusCode());
        Map<String, Object> body = json(response);

        assertTrue((Boolean) body.get("ok"));
        assertEquals(1, ((Number) body.get("day")).intValue());
        assertEquals(0, ((Number) body.get("tickOfDay")).intValue());
        assertEquals("2026-01-01", body.get("date"));
        assertEquals("08:00:00", body.get("time"));
        assertEquals("2026-01-01 08:00:00", body.get("datetime"));
        assertEquals(0L, ((Number) body.get("watermark")).longValue());
        assertFalse((Boolean) body.get("paused"));
        assertEquals("", body.get("pauseReason"));
        // 前端不使用 tick（CompanyStatus 只暴露 DayTick），字段保留但为 null
        assertTrue(body.containsKey("tick"));

        Map<String, Object> web = map(body.get("web"));
        assertEquals(server.port(), ((Number) web.get("port")).intValue());
        // 任何 API 轮询都会刷新浏览器心跳，所以这次 /api/state 自身就把它置为在线
        assertEquals("http://127.0.0.1:" + server.port() + "/", web.get("url"));
        assertTrue((Boolean) web.get("attached"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) body.get("groups");
        assertNotNull(groups);
        assertEquals(2, groups.size());
        // 领导组固定排第一
        assertEquals("Leadership Group", groups.get(0).get("key"));
        assertEquals("Leadership Group", groups.get(0).get("label"));
        assertEquals("研发组", groups.get(1).get("key"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) groups.get(0).get("members");
        assertEquals(2, members.size());
        assertTrue(members.stream().anyMatch(m ->
                "CEO".equals(m.get("roleId")) && "Lin Zong".equals(m.get("name"))));
        assertEquals("ON_DUTY_IDLE", members.get(0).get("state"));

        @SuppressWarnings("unchecked")
        Map<String, Object> clientTalk = (Map<String, Object>) body.get("clientTalk");
        assertFalse((Boolean) clientTalk.get("active"));
        assertTrue((Boolean) clientTalk.get("attached"));
    }

    // ── /api/messages ─────────────────────────────────────────

    @Test
    void messages空初始与增量拉取() throws Exception {
        Map<String, Object> empty = json(get("/api/messages?since=0"));
        assertEquals(0L, ((Number) empty.get("lastSeq")).longValue());
        assertEquals(0L, ((Number) empty.get("watermark")).longValue());
        assertTrue(((List<?>) empty.get("messages")).isEmpty());

        feed.system("系统启动");
        RoleId dev = new RoleId("dev");
        feed.reasoning(dev, "想一想", new Transcript.TraceMeta(new com.agent.software.kernel.Ids.TaskId("t1"), 1));

        Map<String, Object> all = json(get("/api/messages?since=0"));
        assertEquals(2L, ((Number) all.get("lastSeq")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) all.get("messages");
        assertEquals(2, messages.size());
        assertEquals(1L, ((Number) messages.get(0).get("seq")).longValue());
        assertEquals(ChatFeed.KIND_SYSTEM, messages.get(0).get("kind"));
        assertEquals("系统启动", messages.get(0).get("text"));
        assertEquals(ChatFeed.KIND_REASON, messages.get(1).get("kind"));
        assertEquals("t1", map(messages.get(1).get("extra")).get("taskId"));

        Map<String, Object> incremental = json(get("/api/messages?since=1"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tail = (List<Map<String, Object>>) incremental.get("messages");
        assertEquals(1, tail.size());
        assertEquals(2L, ((Number) tail.get(0).get("seq")).longValue());

        // since 非数字按 0 处理（对齐 master）
        Map<String, Object> bogus = json(get("/api/messages?since=abc"));
        assertEquals(2, ((List<?>) bogus.get("messages")).size());
    }

    // ── /api/reply ────────────────────────────────────────────

    @Test
    void reply与等待者会合() throws Exception {
        feed.client(new Transcript.Client(new RoleId("CEO"), "Lin Zong", "Leadership Group", "要做什么系统？"));

        AtomicReference<Optional<String>> result = new AtomicReference<>();
        Thread waiter = new Thread(() -> result.set(feed.awaitClientReply(java.time.Duration.ofSeconds(5))));
        waiter.start();
        try {
            await("等待者注册", 5_000, () -> feed.clientDialogue().waitingRoleId() != null);

            @SuppressWarnings("unchecked")
            Map<String, Object> clientTalk = (Map<String, Object>) json(get("/api/state")).get("clientTalk");
            assertTrue((Boolean) clientTalk.get("active"));
            assertEquals("CEO", clientTalk.get("holderRoleId"));
            assertEquals("Lin Zong", clientTalk.get("holderName"));

            HttpResponse<String> posted = post("/api/reply", "{\"text\":\"请做一个支付系统\"}");
            assertEquals(200, posted.statusCode());
            Map<String, Object> body = json(posted);
            assertTrue((Boolean) body.get("ok"));
            assertTrue((Boolean) body.get("delivered"));
            Map<String, Object> message = map(body.get("message"));
            assertEquals(ChatFeed.KIND_CLIENT, message.get("kind"));
            assertEquals(ChatFeed.CLIENT_NAME, message.get("fromName"));
            assertEquals("CEO", message.get("toRoleId"));
            assertEquals("请做一个支付系统", message.get("text"));

            waiter.join(5_000);
            assertFalse(waiter.isAlive());
            assertEquals("请做一个支付系统", result.get().orElseThrow());

            @SuppressWarnings("unchecked")
            Map<String, Object> afterTalk = (Map<String, Object>) json(get("/api/state")).get("clientTalk");
            assertFalse((Boolean) afterTalk.get("active"));
        } finally {
            waiter.join(1_000);
        }
    }

    @Test
    void reply无等待者时暂存并返回未投递() throws Exception {
        HttpResponse<String> response = post("/api/reply", "{\"text\":\"先存着\"}");
        assertEquals(200, response.statusCode());
        assertFalse((Boolean) json(response).get("delivered"));
        // 暂存后下一次 await 立即取走
        assertEquals("先存着", feed.awaitClientReply(java.time.Duration.ofSeconds(1)).orElseThrow());
    }

    @Test
    void reply参数校验() throws Exception {
        assertEquals(400, post("/api/reply", "{\"text\":\"   \"}").statusCode());
        assertEquals(400, post("/api/reply", "not-json").statusCode());
        assertEquals(400, post("/api/reply", "").statusCode());
        assertEquals(405, get("/api/reply").statusCode());
    }

    @Test
    void 请求体超限返回413() throws Exception {
        String huge = "{\"text\":\"" + "x".repeat((1 << 20) + 8) + "\"}";
        HttpResponse<String> response = post("/api/reply", huge);
        assertEquals(413, response.statusCode());
    }

    // ── /api/email ─────────────────────────────────────────────────

    @Test
    void email作为普通新邮件事件投递给目标角色() throws Exception {
        HttpResponse<String> response = post("/api/email",
                "{\"roleId\":\"CTO\",\"subject\":\"Architecture review\",\"content\":\"Please review the proposal.\"}");
        assertEquals(200, response.statusCode());
        assertTrue((Boolean) json(response).get("ok"));
        assertEquals("Gao Yuan", json(response).get("roleName"));

        AgentEvent event = view.lastEmail.get();
        assertNotNull(event);
        assertEquals(EventKind.NEW_MAIL, event.kind());
        assertEquals("CTO", event.target().orElseThrow().value());
        assertEquals("Client A", event.payload().stringOr("from_name", ""));
        assertEquals("client@external", event.payload().stringOr("from", ""));
        assertEquals("Architecture review", event.payload().subject());
        assertEquals("Please review the proposal.", event.payload().text());
    }

    @Test
    void email参数与角色校验() throws Exception {
        assertEquals(405, get("/api/email").statusCode());
        assertEquals(400, post("/api/email", "not-json").statusCode());
        assertEquals(400, post("/api/email", "{\"roleId\":\"CEO\",\"subject\":\"\",\"content\":\"Hi\"}").statusCode());
        assertEquals(404, post("/api/email", "{\"roleId\":\"ghost\",\"subject\":\"Hi\",\"content\":\"Hi\"}").statusCode());
    }

    // ── /api/pause | /api/resume ──────────────────────────────

    @Test
    void pause与resume() throws Exception {
        assertFalse((Boolean) json(get("/api/state")).get("paused"));

        HttpResponse<String> paused = post("/api/pause", "{\"reason\":\"余额不足（HTTP 402）\"}");
        assertEquals(200, paused.statusCode());
        Map<String, Object> pausedBody = json(paused);
        assertTrue((Boolean) pausedBody.get("ok"));
        assertTrue((Boolean) pausedBody.get("paused"));
        assertEquals("余额不足（HTTP 402）", pausedBody.get("pauseReason"));
        assertTrue(view.paused());
        assertEquals("余额不足（HTTP 402）", view.reason());

        Map<String, Object> state = json(get("/api/state"));
        assertTrue((Boolean) state.get("paused"));
        assertEquals("余额不足（HTTP 402）", state.get("pauseReason"));

        HttpResponse<String> resumed = post("/api/resume", "");
        assertEquals(200, resumed.statusCode());
        Map<String, Object> resumedBody = json(resumed);
        assertTrue((Boolean) resumedBody.get("ok"));
        assertFalse((Boolean) resumedBody.get("paused"));
        assertFalse(view.paused());

        assertFalse((Boolean) json(get("/api/state")).get("paused"));
    }

    @Test
    void pause默认原因与方法守卫() throws Exception {
        assertEquals(405, get("/api/pause").statusCode());
        assertEquals(405, get("/api/resume").statusCode());

        // 空 body → 默认原因
        HttpResponse<String> paused = post("/api/pause", "");
        assertEquals(200, paused.statusCode());
        assertEquals("paused via the Web UI", json(paused).get("pauseReason"));
        assertTrue(view.paused());

        assertEquals(400, post("/api/pause", "not-json").statusCode());

        // 显式 reason 覆盖默认值
        assertEquals(200, post("/api/pause", "{\"reason\":\"手动暂停\"}").statusCode());
        assertEquals("手动暂停", view.reason());
    }

    // ── /api/attach ───────────────────────────────────────────

    @Test
    void attach刷新浏览器心跳() throws Exception {
        assertFalse(feed.clientAttached());
        HttpResponse<String> attached = post("/api/attach", "");
        assertEquals(200, attached.statusCode());
        Map<String, Object> body = json(attached);
        assertTrue((Boolean) body.get("ok"));
        assertTrue((Boolean) body.get("attached"));
        assertTrue(feed.clientAttached());

        @SuppressWarnings("unchecked")
        Map<String, Object> web = (Map<String, Object>) json(get("/api/state")).get("web");
        assertTrue((Boolean) web.get("attached"));
    }

    // ── 假视图 ─────────────────────────────────────────────────

    /** 内存里的假公司视图：roster 固定，pause/resume 只改自身状态。 */
    private static final class FakeView implements CompanyView {

        private final List<RoleSpec> roster;
        private boolean paused;
        private String reason = "";
        private final AtomicReference<AgentEvent> lastEmail = new AtomicReference<>();

        FakeView(List<RoleSpec> roster) {
            this.roster = roster;
        }

        boolean paused() {
            return paused;
        }

        String reason() {
            return reason;
        }

        @Override
        public CompanyStatus status() {
            return new CompanyStatus(new DayTick(1, 0), "2026-01-01 08:00:00", "Day 1 08:00:00（在岗）",
                    paused, reason,
                    List.of(new AgentSnapshot(new RoleId("CEO"), "Lin Zong", AgentState.ON_DUTY_IDLE, false, 0, ""),
                            new AgentSnapshot(new RoleId("CTO"), "Gao Yuan", AgentState.ON_DUTY_IDLE, false, 0, "")));
        }

        @Override
        public List<RoleSpec> roster() {
            return roster;
        }

        @Override
        public void pause(String reason) {
            this.paused = true;
            this.reason = reason == null ? "" : reason;
        }

        @Override
        public void resume() {
            this.paused = false;
            this.reason = "";
        }

        @Override
        public void sendEmail(RoleId recipient, String subject, String body) {
            lastEmail.set(AgentEvent.toRole(recipient, EventKind.NEW_MAIL,
                    com.agent.software.sim.event.Priority.NORMAL,
                    Payload.of("from", "client@external")
                            .with("from_name", "Client A")
                            .with("subject", subject)
                            .with("text", body)));
        }
    }

    private static RoleSpec ceoSpec() {
        return RoleSpec.builder()
                .id(new RoleId("CEO"))
                .name("Lin Zong")
                .username("linzong")
                .title("CEO")
                .group("Leadership Group")
                .toolkits(Set.of("email"))
                .build();
    }

    private static RoleSpec ctoSpec() {
        return RoleSpec.builder()
                .id(new RoleId("CTO"))
                .name("Gao Yuan")
                .username("gaoyuan")
                .title("CTO")
                .group("Leadership Group")
                .toolkits(Set.of("email"))
                .build();
    }

    private static RoleSpec devSpec() {
        return RoleSpec.builder()
                .id(new RoleId("dev"))
                .name("张三")
                .username("zhangsan")
                .title("工程师")
                .group("研发组")
                .toolkits(Set.of("note"))
                .build();
    }

    // ── HTTP 助手 ──────────────────────────────────────────────

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> post(String path, String jsonBody) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> json(HttpResponse<String> response) {
        try {
            return json.readMap(response.body());
        } catch (RuntimeException e) {
            throw new AssertionError("响应不是合法 JSON：" + response.body(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
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
}
