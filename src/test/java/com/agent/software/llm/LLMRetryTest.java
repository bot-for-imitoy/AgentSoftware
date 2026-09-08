package com.agent.software.llm;

import com.agent.software.store.ConfigStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM API request retry tests (the Java counterpart of the Python test_llm_retry.py).
 * Uses a local HTTP server to emulate the chat/completions endpoint (rate limit / 5xx / 4xx / timeout).
 */
class LLMRetryTest {

    /** Fake server returning status codes in order; the last code repeats for remaining requests. */
    private static final class FakeServer implements AutoCloseable {
        final HttpServer server;
        final List<Integer> statuses;
        final AtomicInteger calls = new AtomicInteger(0);
        volatile int sleepMillis = 0;
        volatile int sleepFirstCount = 0;  // only the first N requests sleep (simulate a timeout)

        FakeServer(int... statuses) throws IOException {
            this.statuses = new ArrayList<>();
            for (int s : statuses) {
                this.statuses.add(s);
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.start();
        }

        private void handle(HttpExchange ex) throws IOException {
            int call = calls.incrementAndGet();
            if (sleepMillis > 0 && call <= sleepFirstCount) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int idx = Math.min(calls.get() - 1, statuses.size() - 1);
            int status = statuses.get(Math.max(0, idx));
            String body = status == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"total_tokens\":3}}"
                    : "{\"error\":{\"message\":\"fake error\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private final List<FakeServer> servers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (FakeServer s : servers) {
            s.close();
        }
        servers.clear();
    }

    private FakeServer fake(int... statuses) throws IOException {
        FakeServer s = new FakeServer(statuses);
        servers.add(s);
        return s;
    }

    private OpenAICompatLLM client(FakeServer s) {
        OpenAICompatLLM llm = new OpenAICompatLLM(null, s.baseUrl(), null, null,
                new ConfigStore(), Map.of(), Map.of());
        llm.retryDelay = 0;   // disable the retry delay
        llm.retryMax = 3;
        return llm;
    }

    @Test
    void testSuccessNoRetry() throws IOException {
        FakeServer s = fake(200);
        LLM.ChatResponse r = client(s).chat("s", "u", 0.7, 8);
        assertEquals("ok", r.text);
        assertEquals(3, r.tokens);
        assertEquals(1, s.calls.get());
    }

    @Test
    void test429ThenSuccess() throws IOException {
        FakeServer s = fake(429, 429, 200);
        LLM.ChatResponse r = client(s).chat("s", "u", 0.7, 8);
        assertEquals("ok", r.text);
        assertEquals(3, s.calls.get());
    }

    @Test
    void test5xxRetriesUntilSuccess() throws IOException {
        FakeServer s = fake(500, 503, 200);
        LLM.ChatResponse r = client(s).chat("s", "u", 0.7, 8);
        assertEquals("ok", r.text);
        assertEquals(3, s.calls.get());
    }

    @Test
    void testRetryExhaustedReturnsError() throws IOException {
        FakeServer s = fake(500, 500, 500, 500);
        LLM.ChatResponse r = client(s).chat("s", "u", 0.7, 8);
        assertTrue(r.text.startsWith("[API error: Retried 3 times and still failed"));
        assertEquals(3, s.calls.get());  // gives up after exactly 3 retries
    }

    @Test
    void testClientErrorDoesNotRetry() throws IOException {
        FakeServer s = fake(400);
        LLM.ChatResponse r = client(s).chat("s", "u", 0.7, 8);
        assertTrue(r.text.startsWith("[API error:"));
        assertEquals(1, s.calls.get());
    }

    // ── Insufficient balance / quota (auto-pause trigger) ─────

    @Test
    void testInsufficientBalanceMarkerDetection() {
        // HTTP 402 is always treated as an exhausted-account error
        assertTrue(OpenAICompatLLM.isInsufficientBalance(402, "{}"));
        // … as are error bodies mentioning balance / quota exhaustion (any status)
        assertTrue(OpenAICompatLLM.isInsufficientBalance(200,
                "{\"error\":{\"message\":\"Insufficient Balance\"}}"));
        assertTrue(OpenAICompatLLM.isInsufficientBalance(429,
                "{\"error\":{\"type\":\"insufficient_quota\",\"message\":\"You exceeded your current quota\"}}"));
        assertTrue(OpenAICompatLLM.isInsufficientBalance(403,
                "{\"error\":{\"message\":\"余额不足\"}}"));
        // transient errors are NOT treated as balance exhaustion
        assertFalse(OpenAICompatLLM.isInsufficientBalance(429,
                "{\"error\":{\"message\":\"Rate limit reached, slow down\"}}"));
        assertFalse(OpenAICompatLLM.isInsufficientBalance(500, "server error"));
        assertFalse(OpenAICompatLLM.isInsufficientBalance(200, "ok"));
    }

    @Test
    void test402InsufficientBalanceNoRetryAndNotifies() throws IOException {
        FakeServer s = fake(402);
        AtomicInteger notified = new AtomicInteger(0);
        OpenAICompatLLM llm = client(s);
        llm.setOnInsufficientBalance(reason -> notified.incrementAndGet());
        LLM.ChatResponse r = llm.chat("s", "u", 0.7, 8);
        assertTrue(r.text.startsWith("[API error: HTTP 402"), r.text);
        assertEquals(1, s.calls.get());    // no retries on an empty account
        assertEquals(1, notified.get());   // the auto-pause hook fired
    }

    /** Some backends report quota exhaustion as 429 with an insufficient_quota body — must not retry forever. */
    @Test
    void test429WithInsufficientQuotaBodyNoRetryAndNotifies() throws IOException {
        FakeServer s = new FakeServer(429);
        servers.add(s);
        s.server.removeContext("/v1/chat/completions");
        s.server.createContext("/v1/chat/completions", ex -> {
            s.calls.incrementAndGet();
            String body = "{\"error\":{\"type\":\"insufficient_quota\","
                    + "\"message\":\"You exceeded your current quota, please check your plan and billing details.\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(429, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        AtomicInteger notified = new AtomicInteger(0);
        OpenAICompatLLM llm = client(s);
        llm.setOnInsufficientBalance(reason -> notified.incrementAndGet());
        LLM.ChatResponse r = llm.chat("s", "u", 0.7, 8);
        assertTrue(r.text.startsWith("[API error:"), r.text);
        assertEquals(1, s.calls.get());
        assertEquals(1, notified.get());
    }

    // ── System pause: no request is sent / retried while paused ─

    @Test
    void testPauseGateAbortsRequestWithoutCallingApi() throws IOException {
        FakeServer s = fake(500);
        OpenAICompatLLM llm = client(s);
        llm.setPauseGate(() -> true);   // the owning system is paused
        LLM.ChatResponse r = llm.chat("s", "u", 0.7, 8);
        assertTrue(r.text.contains("system paused"), r.text);
        assertEquals(0, s.calls.get());  // paused → the API is never contacted
    }

    @Test
    void testTimeoutRetries() throws IOException {
        FakeServer s = fake(200);
        s.sleepMillis = 1500;    // the first request is slower than the client timeout (1s) → timeout
        s.sleepFirstCount = 1;   // only the first request sleeps → retry succeeds
        OpenAICompatLLM llm = client(s);
        llm.apiTimeoutSeconds = 1;
        LLM.ChatResponse r = llm.chat("s", "u", 0.7, 8);
        assertEquals("ok", r.text);
        assertEquals(2, s.calls.get());
    }

    @Test
    void testChatWithToolsReturnsToolCalls() throws IOException {
        FakeServer s = new FakeServer(200);
        servers.add(s);
        // override the default response: custom tool-call response
        s.server.removeContext("/v1/chat/completions");
        s.server.createContext("/v1/chat/completions", ex -> {
            s.calls.incrementAndGet();
            String body = "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":["
                    + "{\"id\":\"call_1\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"get_time\",\"arguments\":\"{}\"}}]}}],"
                    + "\"usage\":{\"total_tokens\":5}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        LLM.ToolsResponse r = client(s).chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.of(), 0.7, null);
        assertTrue(r.content.isEmpty());
        assertEquals(1, r.toolCalls.size());
        Map<String, Object> fn = (Map<String, Object>) r.toolCalls.get(0).get("function");
        assertEquals("get_time", fn.get("name"));
        assertEquals(5, r.totalTokens());
    }

    /** reasoning_content (chain of thought) must be carried out of chatWithTools. */
    @Test
    void testChatWithToolsCarriesReasoning() throws IOException {
        FakeServer s = new FakeServer(200);
        servers.add(s);
        s.server.removeContext("/v1/chat/completions");
        s.server.createContext("/v1/chat/completions", ex -> {
            s.calls.incrementAndGet();
            String body = "{\"choices\":[{\"message\":{\"content\":\"final\","
                    + "\"reasoning_content\":\"deep thought\"}}],\"usage\":{\"total_tokens\":7}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        LLM.ToolsResponse r = client(s).chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.of(), 0.7, null);
        assertEquals("deep thought", r.reasoning);
        assertEquals("final", r.content);
        assertEquals(7, r.totalTokens());
    }

    /** Empty content falls back to reasoning_content, but reasoning is still surfaced separately. */
    @Test
    void testReasoningFallbackKeepsReasoningField() throws IOException {
        FakeServer s = new FakeServer(200);
        servers.add(s);
        s.server.removeContext("/v1/chat/completions");
        s.server.createContext("/v1/chat/completions", ex -> {
            s.calls.incrementAndGet();
            String body = "{\"choices\":[{\"message\":{\"content\":\"\","
                    + "\"reasoning_content\":\"thinking out loud\"}}],\"usage\":{\"total_tokens\":9}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        LLM.ToolsResponse r = client(s).chatWithTools(
                List.of(Map.of("role", "user", "content", "hi")), List.of(), 0.7, null);
        assertEquals("thinking out loud", r.content);   // backward-compatible fallback
        assertEquals("thinking out loud", r.reasoning); // reasoning is still exposed to the trace layer
    }

    /** Plain chat also carries reasoning_content (used by the no-tools role path). */
    @Test
    void testChatCarriesReasoning() throws IOException {
        FakeServer s = new FakeServer(200);
        servers.add(s);
        s.server.removeContext("/v1/chat/completions");
        s.server.createContext("/v1/chat/completions", ex -> {
            s.calls.incrementAndGet();
            String body = "{\"choices\":[{\"message\":{\"content\":\"answer\","
                    + "\"reasoning_content\":\"cot\"}}],\"usage\":{\"total_tokens\":4}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        LLM.ChatResponse r = client(s).chat("s", "u", 0.7, 8);
        assertEquals("answer", r.text);
        assertEquals("cot", r.reasoning);
    }

    // ── Config precedence (Java args > env vars > ConfigStore) ────

    /** Without system properties/env vars, config falls back to ConfigStore (llm.*). */
    @Test
    void testConfigFileUsedWhenNoSystemSources(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        config.update(Map.of(
                "llm.api_key", "config-key",
                "llm.base_url", "http://config.example/",
                "llm.model", "config-model"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of(), Map.of());
        assertEquals("config-key", client.apiKey);
        assertEquals("http://config.example", client.baseUrl);
        assertEquals("config-model", client.model);
    }

    /** Env vars take precedence over the config file. */
    @Test
    void testEnvironmentPrecedesConfigFile(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        config.update(Map.of("llm.base_url", "http://config.example/"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of("OPENAI_BASE_URL", "http://env.example/"), Map.of());
        assertEquals("http://env.example", client.baseUrl);
    }

    /** Java args (-D system properties) take precedence over env vars. */
    @Test
    void testSystemPropertyPrecedesEnvironment(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of("OPENAI_BASE_URL", "http://env.example/"),
                Map.of("OPENAI_BASE_URL", "http://prop.example/"));
        assertEquals("http://prop.example", client.baseUrl);
    }

    /** Explicit constructor args take precedence over all sources. */
    @Test
    void testExplicitArgumentPrecedesAllSources(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        OpenAICompatLLM client = new OpenAICompatLLM("explicit-key", "http://explicit.example", "explicit-model",
                null, config,
                Map.of("OPENAI_BASE_URL", "http://env.example/"),
                Map.of("OPENAI_BASE_URL", "http://prop.example/"));
        assertEquals("explicit-key", client.apiKey);
        assertEquals("http://explicit.example", client.baseUrl);
        assertEquals("explicit-model", client.model);
    }

    /** With no sources, OpenAI defaults are used; the API key is not required. */
    @Test
    void testDefaultsWithoutAnySource(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of(), Map.of());
        assertNull(client.apiKey);
        assertEquals(OpenAICompatLLM.DEFAULT_BASE_URL, client.baseUrl);
        assertEquals(OpenAICompatLLM.DEFAULT_MODEL, client.model);
    }

    /** The API key also follows the OpenAI env var (OPENAI_API_KEY). */
    @Test
    void testOpenaiApiKeyFromEnvironment(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of("OPENAI_API_KEY", "sk-env"), Map.of());
        assertEquals("sk-env", client.apiKey);
    }

    /** The config file's llm.api_key / llm.model are read individually. */
    @Test
    void testOpenaiApiKeyFromConfigFile(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        config.update(Map.of("llm.api_key", "sk-config"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of(), Map.of());
        assertEquals("sk-config", client.apiKey);
    }
}
