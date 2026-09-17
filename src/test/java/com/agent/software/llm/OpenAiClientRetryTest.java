package com.agent.software.llm;

import com.agent.software.infra.config.AppConfig;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.llm.LlmClient.ChatReply;
import com.agent.software.llm.LlmClient.ChatRequest;
import com.agent.software.llm.LlmClient.ToolChatRequest;
import com.agent.software.llm.LlmClient.ToolReply;
import com.agent.software.tool.spi.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link OpenAiClient} 的重试 / 限流 / 余额不足 / 暂停门 / 请求体形状测试。
 *
 * <p>迁移自 master 的 {@code LLMRetryTest}（Python {@code test_llm_retry.py} 的 Java 版），
 * 但适配新架构：
 * <ul>
 *   <li>失败不再返回 {@code "[API error: ...]"} 文本，而是空 {@link ChatReply}
 *       （{@link ChatReply#failed()} 为 true）；因此所有断言改为判空而不是嗅探字符串前缀；</li>
 *   <li>{@code LLM} 接口改为 {@link LlmClient}，{@code OpenAICompatLLM} 改为
 *       {@link OpenAiClient(endpoint, retry, arbiter)}；</li>
 *   <li>重试/超时/延迟改由 {@link AppConfig.Llm.Retry} 注入，退避调成 0.05 秒避免测试变慢；</li>
 *   <li>暂停门语义变化：master 暂停时立即返回 {@code "aborted: system paused"}，
 *       新实现改为"暂停期间不发请求，轮询等待恢复后再发"，因此测试验证的是
 *       "暂停期间 {@code calls == 0}，恢复后才发出请求"。</li>
 * </ul>
 *
 * <p>HTTP 服务用 JDK 自带的 {@link HttpServer}，不引入 mock 框架。
 */
class OpenAiClientRetryTest {

    /** 退避时间：小到不影响测试时长（生产默认 10 秒）。 */
    private static final double DELAY_SECONDS = 0.05;

    private static final ChatRequest CHAT = new ChatRequest("sys", "usr", 0.7, 8);

    private static void awaitTrue(String what, BooleanSupplier condition) {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("等待超时: " + what);
    }

    /**
     * 假 chat/completions 服务：按顺序返回给定状态码（最后一个重复），记录每次请求的
     * 请求体与请求头，以便断言线格式。
     */
    private static final class MockServer implements AutoCloseable {
        final HttpServer server;
        final List<Integer> statuses = new ArrayList<>();
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        /** 前 sleepFirstCount 次请求各睡 sleepMillis 毫秒（模拟超时）。 */
        volatile int sleepMillis;
        volatile int sleepFirstCount;
        volatile String successBody =
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"total_tokens\":3}}";
        volatile String errorBody = "{\"error\":{\"message\":\"fake error\"}}";
        private final ExecutorService executor = Executors.newCachedThreadPool();

        MockServer(int... statuses) throws IOException {
            for (int status : statuses) {
                this.statuses.add(status);
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            // 必须每个请求一个线程：默认执行器会把请求串行化，掩盖客户端的并发行为。
            server.setExecutor(executor);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            int call = calls.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Map<String, String> recorded = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((name, values) ->
                    recorded.put(name.toLowerCase(Locale.ROOT), values.isEmpty() ? "" : values.get(0)));
            headers.add(recorded);

            if (sleepMillis > 0 && call <= sleepFirstCount) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = statusFor(call);
            byte[] bytes = (status == 200 ? successBody : errorBody).getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {
                // 客户端超时后主动断开连接：服务端写入失败是预期内的。
            } finally {
                exchange.close();
            }
        }

        private int statusFor(int call) {
            if (statuses.isEmpty()) {
                return 200;
            }
            int index = Math.min(call - 1, statuses.size() - 1);
            return statuses.get(Math.max(0, index));
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        JsonNode lastBody() throws IOException {
            return new ObjectMapper().readTree(bodies.get(bodies.size() - 1));
        }

        String header(int index, String name) {
            return headers.get(index).get(name.toLowerCase(Locale.ROOT));
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private final List<MockServer> servers = new ArrayList<>();

    @BeforeEach
    void isolateSharedArbiters() {
        RetryArbiter.clearShared();
    }

    @AfterEach
    void cleanup() {
        for (MockServer server : servers) {
            server.close();
        }
        servers.clear();
        RetryArbiter.clearShared();
    }

    private MockServer fake(int... statuses) throws IOException {
        MockServer server = new MockServer(statuses);
        servers.add(server);
        return server;
    }

    private OpenAiClient client(MockServer server, int maxAttempts) {
        return client(server, maxAttempts, null, 5, null);
    }

    private OpenAiClient client(MockServer server, int maxAttempts, String apiKey,
                                int timeoutSeconds, RetryArbiter arbiter) {
        ProviderResolver.Endpoint endpoint =
                new ProviderResolver.Endpoint(server.baseUrl() + "/v1", apiKey, "test-model");
        AppConfig.Llm.Retry retry = new AppConfig.Llm.Retry(maxAttempts, DELAY_SECONDS, timeoutSeconds);
        return new OpenAiClient(endpoint, retry, arbiter);
    }

    // ── 成功与可重试错误 ────────────────────────────────────

    @Test
    void testSuccessNoRetry() throws IOException {
        MockServer server = fake(200);
        ChatReply reply = client(server, 3).chat(CHAT);
        assertEquals("ok", reply.text());
        assertEquals(3, reply.tokens());
        assertFalse(reply.failed());
        assertEquals(1, server.calls.get());
    }

    @Test
    void test429ThenSuccess() throws IOException {
        MockServer server = fake(429, 429, 200);
        ChatReply reply = client(server, 3).chat(CHAT);
        assertFalse(reply.failed());
        assertEquals("ok", reply.text());
        assertEquals(3, server.calls.get());
    }

    @Test
    void test5xxRetriesUntilSuccess() throws IOException {
        MockServer server = fake(500, 503, 200);
        ChatReply reply = client(server, 3).chat(CHAT);
        assertFalse(reply.failed());
        assertEquals("ok", reply.text());
        assertEquals(3, server.calls.get());
    }

    @Test
    void testRetryExhaustedReturnsFailedReply() throws IOException {
        MockServer server = fake(500, 500, 500, 500);
        ChatReply reply = client(server, 3).chat(CHAT);
        assertTrue(reply.failed(), "重试耗尽必须返回失败的空回复，而不是 [API error: ...] 文本");
        assertEquals("", reply.text());
        assertEquals(3, server.calls.get(), "maxAttempts=3 时恰好尝试 3 次");
    }

    @Test
    void testClientError400DoesNotRetry() throws IOException {
        MockServer server = fake(400);
        ChatReply reply = client(server, 3).chat(CHAT);
        assertTrue(reply.failed());
        assertEquals(1, server.calls.get());
    }

    @Test
    void testUnauthorized401DoesNotRetry() throws IOException {
        MockServer server = fake(401);
        ChatReply reply = client(server, 5).chat(CHAT);
        assertTrue(reply.failed());
        assertEquals(1, server.calls.get(), "401 是不可重试的客户端错误");
    }

    @Test
    void testForbidden403DoesNotRetry() throws IOException {
        MockServer server = fake(403);
        ChatReply reply = client(server, 5).chat(CHAT);
        assertTrue(reply.failed());
        assertEquals(1, server.calls.get(), "403 是不可重试的客户端错误");
    }

    // ── 余额 / 配额耗尽：回调恰好一次 ───────────────────────

    /** 用给定状态码与响应体跑一次请求，返回余额不足回调被触发的次数（断言请求最终失败）。 */
    private int callbackCountFor(int status, String responseBody) throws IOException {
        MockServer server = fake(status);
        if (status == 200) {
            server.successBody = responseBody;
        } else {
            server.errorBody = responseBody;
        }
        OpenAiClient client = client(server, 5);
        AtomicInteger notified = new AtomicInteger();
        client.setOnInsufficientBalance(reason -> notified.incrementAndGet());
        ChatReply reply = client.chat(CHAT);
        assertTrue(reply.failed(), "余额/瞬态错误后请求都必须是失败回复: status=" + status);
        return notified.get();
    }

    @Test
    void testInsufficientBalanceVariantsFireCallbackExactlyOnce() throws IOException {
        // HTTP 402（Payment Required）永远视为账户耗尽
        assertEquals(1, callbackCountFor(402, "{}"));
        // 响应体命中余额 / 配额关键词（任意状态码）
        assertEquals(1, callbackCountFor(200,
                "{\"error\":{\"message\":\"Insufficient Balance\"}}"));
        assertEquals(1, callbackCountFor(429,
                "{\"error\":{\"type\":\"insufficient_quota\",\"message\":\"You exceeded your current quota\"}}"));
        assertEquals(1, callbackCountFor(403,
                "{\"error\":{\"message\":\"余额不足\"}}"));
    }

    @Test
    void testTransientErrorsAreNotTreatedAsInsufficientBalance() throws IOException {
        assertEquals(0, callbackCountFor(429,
                "{\"error\":{\"message\":\"Rate limit reached, slow down\"}}"));
        assertEquals(0, callbackCountFor(500, "server error"));
    }

    @Test
    void test402InsufficientBalanceNoRetryAndNotifies() throws IOException {
        MockServer server = fake(402);
        OpenAiClient client = client(server, 5);
        AtomicInteger notified = new AtomicInteger();
        client.setOnInsufficientBalance(reason -> {
            assertNotNull(reason);
            notified.incrementAndGet();
        });
        ChatReply reply = client.chat(CHAT);
        assertTrue(reply.failed());
        assertEquals(1, server.calls.get(), "空账户不得重试");
        assertEquals(1, notified.get(), "自动暂停回调必须恰好触发一次");
    }

    /** 有些后端把配额耗尽报成 429 + insufficient_quota 响应体，必须立即失败而不是永远重试。 */
    @Test
    void test429WithInsufficientQuotaBodyNoRetryAndNotifies() throws IOException {
        MockServer server = fake(429);
        server.errorBody = "{\"error\":{\"type\":\"insufficient_quota\","
                + "\"message\":\"You exceeded your current quota, please check your plan and billing details.\"}}";
        OpenAiClient client = client(server, 5);
        AtomicInteger notified = new AtomicInteger();
        client.setOnInsufficientBalance(reason -> notified.incrementAndGet());
        ChatReply reply = client.chat(CHAT);
        assertTrue(reply.failed());
        assertEquals(1, server.calls.get());
        assertEquals(1, notified.get());
    }

    // ── 系统暂停：暂停期间不发请求 ──────────────────────────

    @Test
    void testPauseGateSendsNoRequestUntilResumed() throws Exception {
        MockServer server = fake(200);
        OpenAiClient client = client(server, 3);
        AtomicBoolean paused = new AtomicBoolean(true);
        AtomicInteger gatePolls = new AtomicInteger();
        client.setPausedGate(() -> {
            gatePolls.incrementAndGet();
            return paused.get();
        });

        CompletableFuture<ChatReply> pending =
                CompletableFuture.supplyAsync(() -> client.chat(CHAT));
        // 等暂停门被轮询两次（约 200ms），足以证明客户端在暂停期间一圈圈等待。
        awaitTrue("暂停门被轮询", () -> gatePolls.get() >= 2);
        assertEquals(0, server.calls.get(), "暂停期间绝不能调用 API");
        assertFalse(pending.isDone());

        paused.set(false);
        ChatReply reply = pending.get(5, TimeUnit.SECONDS);
        assertFalse(reply.failed());
        assertEquals("ok", reply.text());
        assertEquals(1, server.calls.get(), "恢复后只发一次请求");
    }

    // ── 超时 ────────────────────────────────────────────────

    @Test
    void testTimeoutRetries() throws IOException {
        MockServer server = fake(200);
        server.sleepMillis = 1500;    // 第一次比客户端超时（1 秒）慢 → 超时
        server.sleepFirstCount = 1;   // 只有第一次睡 → 重试成功

        ChatReply reply = client(server, 3, null, 1, null).chat(CHAT);
        assertFalse(reply.failed());
        assertEquals("ok", reply.text());
        assertEquals(2, server.calls.get());
    }

    // ── 响应解析 ────────────────────────────────────────────

    @Test
    void testChatWithToolsReturnsToolCalls() throws IOException {
        MockServer server = fake(200);
        server.successBody = "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":["
                + "{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"get_time\",\"arguments\":\"{}\"}}]}}],"
                + "\"usage\":{\"total_tokens\":5}}";
        ToolReply reply = client(server, 3).chatWithTools(new ToolChatRequest(
                List.of(Message.user("hi")), List.of(), 0.7, null));
        assertTrue(reply.content().isEmpty());
        assertEquals(1, reply.toolCalls().size());
        assertEquals("call_1", reply.toolCalls().get(0).callId());
        assertEquals("get_time", reply.toolCalls().get(0).toolName());
        assertTrue(reply.toolCalls().get(0).arguments().asMap().isEmpty());
        assertEquals(5, reply.totalTokens());
    }

    @Test
    void testToolCallWithoutIdGetsSyntheticId() throws IOException {
        MockServer server = fake(200);
        server.successBody = "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":["
                + "{\"type\":\"function\",\"function\":{\"name\":\"ping\",\"arguments\":\"{}\"}}]}}]}";
        ToolReply reply = client(server, 3).chatWithTools(new ToolChatRequest(
                List.of(Message.user("hi")), List.of(), 0.7, null));
        assertEquals(1, reply.toolCalls().size());
        assertEquals("call_0", reply.toolCalls().get(0).callId(),
                "provider 没给 id 时必须补一个，保证 tool 结果能回填");
    }

    /** reasoning_content（思维链）必须从 chatWithTools 透出。 */
    @Test
    void testChatWithToolsCarriesReasoning() throws IOException {
        MockServer server = fake(200);
        server.successBody = "{\"choices\":[{\"message\":{\"content\":\"final\","
                + "\"reasoning_content\":\"deep thought\"}}],\"usage\":{\"total_tokens\":7}}";
        ToolReply reply = client(server, 3).chatWithTools(new ToolChatRequest(
                List.of(Message.user("hi")), List.of(), 0.7, null));
        assertEquals("deep thought", reply.reasoning());
        assertEquals("final", reply.content());
        assertEquals(7, reply.totalTokens());
    }

    /** content 为空时回退用 reasoning_content，但 reasoning 字段仍单独保留。 */
    @Test
    void testReasoningFallbackKeepsReasoningField() throws IOException {
        MockServer server = fake(200);
        server.successBody = "{\"choices\":[{\"message\":{\"content\":\"\","
                + "\"reasoning_content\":\"thinking out loud\"}}],\"usage\":{\"total_tokens\":9}}";
        ToolReply reply = client(server, 3).chatWithTools(new ToolChatRequest(
                List.of(Message.user("hi")), List.of(), 0.7, null));
        assertEquals("thinking out loud", reply.content());
        assertEquals("thinking out loud", reply.reasoning());
    }

    /** 普通 chat 也要透出 reasoning_content（无工具的角色路径在用）。 */
    @Test
    void testChatCarriesReasoning() throws IOException {
        MockServer server = fake(200);
        server.successBody = "{\"choices\":[{\"message\":{\"content\":\"answer\","
                + "\"reasoning_content\":\"cot\"}}],\"usage\":{\"total_tokens\":4}}";
        ChatReply reply = client(server, 3).chat(CHAT);
        assertEquals("answer", reply.text());
        assertEquals("cot", reply.reasoning());
    }

    // ── usage.total_tokens 解析 ─────────────────────────────

    @Test
    void testUsageTotalTokensPreferredThenSummedThenZero() throws IOException {
        MockServer explicitTotal = fake(200);
        explicitTotal.successBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":4,\"total_tokens\":6}}";
        assertEquals(6, client(explicitTotal, 3).chat(CHAT).tokens());

        MockServer onlyParts = fake(200);
        onlyParts.successBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],"
                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":4}}";
        assertEquals(6, client(onlyParts, 3).chat(CHAT).tokens(),
                "缺 total_tokens 时用 prompt_tokens + completion_tokens");

        MockServer noUsage = fake(200);
        noUsage.successBody = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        assertEquals(0, client(noUsage, 3).chat(CHAT).tokens());
    }

    // ── 请求体形状（线格式）──────────────────────────────────

    @Test
    void testChatRequestBodyShapeAndAuthorization() throws IOException {
        MockServer server = fake(200);
        client(server, 3, "sk-test", 5, null).chat(
                new ChatRequest("sys prompt", "user prompt", 0.7, 8));

        JsonNode body = server.lastBody();
        assertEquals("test-model", body.get("model").asText());
        assertEquals(0.7, body.get("temperature").asDouble(), 1e-9);
        assertEquals(8, body.get("max_tokens").asInt());
        assertFalse(body.has("tools"), "没有工具时不得出现 tools 字段");

        JsonNode messages = body.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("sys prompt", messages.get(0).get("content").asText());
        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("user prompt", messages.get(1).get("content").asText());

        assertEquals("Bearer sk-test", server.header(0, "Authorization"));
        assertEquals("application/json", server.header(0, "Content-Type"));
    }

    @Test
    void testApiKeyAbsentFromAuthorizationWhenBlank() throws IOException {
        MockServer server = fake(200);
        client(server, 3).chat(new ChatRequest(null, "u", 0.7, null));

        JsonNode body = server.lastBody();
        assertEquals(1, body.get("messages").size(), "system 为空时不应出现 system 消息");
        assertEquals("user", body.get("messages").get(0).get("role").asText());
        assertFalse(body.has("max_tokens"), "maxTokens 为 null 时不得出现 max_tokens");
        assertNull(server.header(0, "Authorization"), "没有 key 时不带 Authorization，便于连本地 mock");
    }

    @Test
    void testToolsRequestBodyShape() throws IOException {
        MockServer server = fake(200);
        ToolSpec spec = new ToolSpec("get_time", "获取当前时间",
                JsonSchema.object().string("zone", "时区").required("zone"));
        List<Message> messages = List.of(
                Message.user("几点了"),
                Message.assistant("", List.of(
                        new ToolCallRequest("call_1", "get_time", Payload.of("zone", "UTC")))),
                Message.tool("call_1", "12:00"));

        client(server, 3).chatWithTools(new ToolChatRequest(messages, List.of(spec), 0.7, null));

        JsonNode body = server.lastBody();
        assertEquals("auto", body.get("tool_choice").asText());

        JsonNode tools = body.get("tools");
        assertEquals(1, tools.size());
        assertEquals("function", tools.get(0).get("type").asText());
        assertEquals("get_time", tools.get(0).get("function").get("name").asText());
        assertEquals("获取当前时间", tools.get(0).get("function").get("description").asText());
        JsonNode parameters = tools.get(0).get("function").get("parameters");
        assertEquals("object", parameters.get("type").asText());
        assertEquals("string", parameters.get("properties").get("zone").get("type").asText());
        assertEquals("zone", parameters.get("required").get(0).asText());

        JsonNode messagesNode = body.get("messages");
        assertEquals(3, messagesNode.size());
        assertEquals("assistant", messagesNode.get(1).get("role").asText());
        JsonNode call = messagesNode.get(1).get("tool_calls").get(0);
        assertEquals("call_1", call.get("id").asText());
        assertEquals("function", call.get("type").asText());
        assertEquals("get_time", call.get("function").get("name").asText());
        // OpenAI 的 arguments 是 JSON 字符串而不是对象
        assertTrue(call.get("function").get("arguments").isTextual());
        JsonNode arguments = new ObjectMapper().readTree(call.get("function").get("arguments").asText());
        assertEquals("UTC", arguments.get("zone").asText());

        JsonNode toolMessage = messagesNode.get(2);
        assertEquals("tool", toolMessage.get("role").asText());
        assertEquals("call_1", toolMessage.get("tool_call_id").asText());
        assertEquals("12:00", toolMessage.get("content").asText());
    }

    // ── 摘要 ────────────────────────────────────────────────

    @Test
    void testSummarizeGoesThroughChatAndReturnsText() throws IOException {
        MockServer server = fake(200);
        server.successBody = "{\"choices\":[{\"message\":{\"content\":\"总结\"}}],"
                + "\"usage\":{\"total_tokens\":11}}";
        ChatReply reply = client(server, 3).summarize("今天做了很多事", 0.3, 256);
        assertEquals("总结", reply.text());
        assertEquals(11, reply.tokens());

        JsonNode body = server.lastBody();
        assertEquals("system", body.get("messages").get(0).get("role").asText());
        assertTrue(body.get("messages").get(0).get("content").asText().contains("总结"),
                "摘要请求必须带总结用 system 提示词");
        assertTrue(body.get("messages").get(1).get("content").asText().contains("今天做了很多事"));
        assertEquals(256, body.get("max_tokens").asInt());
    }

    @Test
    void testSummarizeFailureReturnsEmptyReply() throws IOException {
        MockServer server = fake(500);
        ChatReply reply = client(server, 1).summarize("日志", 0.3, 256);
        assertTrue(reply.failed());
        assertEquals("", reply.text());
    }
}
