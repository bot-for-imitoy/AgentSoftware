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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM API 请求重试测试 (Python 版 test_llm_retry.py 的 Java 对应物).
 * 用本地 HTTP 服务器模拟 chat/completions 端点 (限速/5xx/4xx/超时).
 */
class LLMRetryTest {

    /** 按序返回状态码的假服务器; 最后一个状态码重复用于剩余请求. */
    private static final class FakeServer implements AutoCloseable {
        final HttpServer server;
        final List<Integer> statuses;
        final AtomicInteger calls = new AtomicInteger(0);
        volatile int sleepMillis = 0;
        volatile int sleepFirstCount = 0;  // 仅前 N 个请求睡眠 (模拟超时)

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
        llm.retryDelay = 0;   // 关闭重试延时
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
        assertTrue(r.text.startsWith("[API error: 重试 3 次仍失败"));
        assertEquals(3, s.calls.get());  // 恰好重试 3 次后放弃
    }

    @Test
    void testClientErrorDoesNotRetry() throws IOException {
        FakeServer s = fake(400);
        LLM.ChatResponse r = client(s).chat("s", "u", 0.7, 8);
        assertTrue(r.text.startsWith("[API error:"));
        assertEquals(1, s.calls.get());
    }

    @Test
    void testTimeoutRetries() throws IOException {
        FakeServer s = fake(200);
        s.sleepMillis = 1500;    // 首个请求慢于客户端超时 (1s) → 超时
        s.sleepFirstCount = 1;   // 仅首个请求睡眠 → 重试成功
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
        // 覆盖默认响应: 自定义工具调用响应
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

    // ── 配置优先级 (Java 参数 > 环境变量 > ConfigStore) ──────

    /** 无系统属性/环境变量时, 配置回落到 ConfigStore (llm.*). */
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

    /** 环境变量优先于配置文件. */
    @Test
    void testEnvironmentPrecedesConfigFile(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        config.update(Map.of("llm.base_url", "http://config.example/"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of("OPENAI_BASE_URL", "http://env.example/"), Map.of());
        assertEquals("http://env.example", client.baseUrl);
    }

    /** Java 参数 (-D 系统属性) 优先于环境变量. */
    @Test
    void testSystemPropertyPrecedesEnvironment(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of("OPENAI_BASE_URL", "http://env.example/"),
                Map.of("OPENAI_BASE_URL", "http://prop.example/"));
        assertEquals("http://prop.example", client.baseUrl);
    }

    /** 构造器显式参数优先于一切来源. */
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

    /** 无任何来源时使用 OpenAI 默认值, 不强制 API Key. */
    @Test
    void testDefaultsWithoutAnySource(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of(), Map.of());
        assertNull(client.apiKey);
        assertEquals(OpenAICompatLLM.DEFAULT_BASE_URL, client.baseUrl);
        assertEquals(OpenAICompatLLM.DEFAULT_MODEL, client.model);
    }

    /** API Key 也走 OpenAI 环境变量 (OPENAI_API_KEY). */
    @Test
    void testOpenaiApiKeyFromEnvironment(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of("OPENAI_API_KEY", "sk-env"), Map.of());
        assertEquals("sk-env", client.apiKey);
    }

    /** 配置文件 llm.api_key / llm.model 单独读取. */
    @Test
    void testOpenaiApiKeyFromConfigFile(@TempDir Path tmp) {
        ConfigStore config = new ConfigStore(tmp.resolve("config.json"));
        config.update(Map.of("llm.api_key", "sk-config"));
        OpenAICompatLLM client = new OpenAICompatLLM(null, null, null, null, config,
                Map.of(), Map.of());
        assertEquals("sk-config", client.apiKey);
    }
}
