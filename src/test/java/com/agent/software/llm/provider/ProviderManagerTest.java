package com.agent.software.llm.provider;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the LLM Provider manager: default catalog loading, local file
 * merging (add / override / disable), API-key resolution and model list
 * fetching through the /models endpoint for both API formats (OpenAI and
 * Anthropic), including auth headers, error mapping and the model cache.
 * All HTTP interactions run against a local stub server.
 */
class ProviderManagerTest {

    /** One recorded request (path + request headers). */
    private static final class RequestRecord {
        final String path;
        final Map<String, String> headers = new LinkedHashMap<>();

        RequestRecord(String path) {
            this.path = path;
        }
    }

    /** Fake provider API: serves a configurable /v1/models response and records requests. */
    private static final class FakeServer implements AutoCloseable {
        final HttpServer server;
        final List<RequestRecord> records = new ArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        volatile String body;
        volatile int status = 200;

        FakeServer(String body) throws IOException {
            this.body = body;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/models", this::handle);
            server.start();
        }

        private void handle(HttpExchange ex) throws IOException {
            calls.incrementAndGet();
            synchronized (records) {
                RequestRecord record = new RequestRecord(ex.getRequestURI().getPath());
                for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                    String name = h.getKey().toLowerCase(Locale.ROOT);
                    record.headers.put(name, h.getValue().isEmpty() ? "" : h.getValue().get(0));
                }
                records.add(record);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }

        /** Header value of the i-th request (case-insensitive), or null. */
        String header(int index, String name) {
            synchronized (records) {
                if (index >= records.size()) {
                    return null;
                }
                return records.get(index).headers.get(name.toLowerCase(Locale.ROOT));
            }
        }

        String path(int index) {
            synchronized (records) {
                return index < records.size() ? records.get(index).path : null;
            }
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
    }

    private FakeServer newServer(String body) throws IOException {
        FakeServer s = new FakeServer(body);
        servers.add(s);
        return s;
    }

    private static Path writeLocal(Path dir, String json) throws IOException {
        Path file = dir.resolve("providers.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    // ── Default catalog ─────────────────────────────────────

    @Test
    void defaultsContainMainstreamProviders() throws Exception {
        ProviderManager manager = ProviderManager.loadDefaults();
        Set<String> expected = Set.of("openai", "anthropic", "google-gemini", "deepseek",
                "mistral", "groq", "openrouter", "together", "xai", "moonshot", "zhipu",
                "dashscope", "siliconflow", "cerebras", "nvidia", "ollama", "vllm", "lm-studio");
        List<String> ids = manager.all().stream().map(Provider::id).toList();
        assertTrue(ids.containsAll(expected), "default catalog ids: " + ids);

        Provider openai = manager.require("openai");
        assertEquals(Provider.ApiFormat.OPENAI, openai.apiFormat());
        assertEquals("https://api.openai.com/v1", openai.baseUrl());
        assertEquals("https://api.openai.com/v1/models", openai.modelsUrl());
        assertEquals("/chat/completions", openai.chatCompletionsPath());
        assertEquals("OPENAI_API_KEY", openai.apiKeyEnv());
        assertTrue(openai.enabled());

        Provider anthropic = manager.require("anthropic");
        assertEquals(Provider.ApiFormat.ANTHROPIC, anthropic.apiFormat());
        assertEquals("https://api.anthropic.com/v1/models", anthropic.modelsUrl());
        assertEquals("/messages", anthropic.chatCompletionsPath());
        assertEquals("x-api-key", anthropic.authHeader());
        assertEquals("2023-06-01", anthropic.headers().get("anthropic-version"));

        // every default entry is enabled and has a well-formed endpoint
        assertEquals(ids.size(), manager.enabled().size());
        for (Provider p : manager.all()) {
            assertNotNull(p.name(), "provider name for " + p.id());
            assertTrue(p.modelsUrl().startsWith("http"), p.modelsUrl());
            assertTrue(p.modelsUrl().endsWith("/models"), p.modelsUrl());
        }
        // ids unique
        assertEquals(ids.size(), Set.copyOf(ids).size());
    }

    // ── Local config merging ────────────────────────────────

    @Test
    void localFileAddsOverridesAndDisables(@TempDir Path dir) throws Exception {
        ProviderManager defaults = ProviderManager.loadDefaults();
        Path local = writeLocal(dir, """
                {
                  "api_keys": { "openai": "sk-local-secret" },
                  "providers": [
                    { "id": "deepseek", "base_url": "https://myproxy.example.com/v1" },
                    { "id": "groq", "enabled": false },
                    { "id": "my-llm", "name": "My LLM", "api_format": "openai",
                      "base_url": "http://localhost:9999/v1", "enabled": true }
                  ]
                }
                """);
        ProviderManager manager = ProviderManager.load(local);

        // one new provider appended, defaults otherwise untouched in count
        assertEquals(defaults.all().size() + 1, manager.all().size());
        assertEquals("my-llm", manager.all().get(manager.all().size() - 1).id());
        // existing provider merged field by field (base_url replaced, other fields kept)
        Provider deepseek = manager.require("deepseek");
        assertEquals("https://myproxy.example.com/v1", deepseek.baseUrl());
        assertEquals("DEEPSEEK_API_KEY", deepseek.apiKeyEnv());
        // disabled provider: still findable, excluded from enabled()/fetching
        Provider groq = manager.require("groq");
        assertFalse(groq.enabled());
        assertEquals(defaults.enabled().size(), manager.enabled().size());
        assertFalse(manager.enabled().stream().anyMatch(p -> p.id().equals("groq")));
        // local api key registered
        assertEquals(Optional.of("sk-local-secret"), manager.explicitApiKey("openai"));
        assertThrows(ProviderException.class, () -> manager.listModels("groq"));
    }

    // ── Model list: OpenAI dialect ──────────────────────────

    @Test
    void fetchModelsOpenAiFormatWithBearerAuth(@TempDir Path dir) throws Exception {
        FakeServer server = newServer("""
                {"object":"list","data":[
                  {"id":"gpt-4o-mini","object":"model","created":1715368132,"owned_by":"openai"},
                  {"id":"deepseek-chat","object":"model","created":1700000000,"owned_by":"deepseek"},
                  {"id":"friendly","name":"A Friendly Model"}
                ]}
                """);
        Path local = writeLocal(dir, """
                {
                  "api_keys": { "openai": "sk-test-openai" },
                  "providers": [ { "id": "openai", "base_url": "%s/v1" } ]
                }
                """.formatted(server.baseUrl()));
        ProviderManager manager = ProviderManager.load(local);

        List<ModelInfo> models = manager.listModels("openai");
        assertEquals(3, models.size());
        List<String> ids = models.stream().map(ModelInfo::id).toList();
        assertEquals(List.of("gpt-4o-mini", "deepseek-chat", "friendly"), ids);
        // created epoch seconds -> epoch millis
        assertEquals(1715368132L * 1000, models.get(0).createdAtMillis());
        // owned_by from the OpenAI dialect
        assertEquals("openai", models.get(0).ownedBy());
        // display name falls back to the id / uses "name"
        assertEquals("gpt-4o-mini", models.get(0).displayName());
        assertEquals("A Friendly Model", models.get(2).displayName());
        // raw entry preserved
        assertEquals("model", models.get(0).raw().get("object"));

        // correct request: path, bearer auth header, no static headers
        assertEquals("/v1/models", server.path(0));
        assertEquals("Bearer sk-test-openai", server.header(0, "Authorization"));

        // findModel by id
        assertTrue(manager.findModel("openai", "gpt-4o-mini").isPresent());
        assertTrue(manager.findModel("openai", "no-such-model").isEmpty());

        // caching: second call is served without a new HTTP request
        manager.listModels("openai");
        assertEquals(1, server.calls.get());
        manager.clearCache();
        manager.listModels("openai");
        assertEquals(2, server.calls.get());
    }

    // ── Model list: Anthropic dialect ───────────────────────

    @Test
    void fetchModelsAnthropicFormatWithApiKeyHeader(@TempDir Path dir) throws Exception {
        FakeServer server = newServer("""
                {"data":[
                  {"type":"model","id":"claude-sonnet-4-20250514",
                   "display_name":"Claude Sonnet 4","created_at":"2025-05-14T00:00:00Z"},
                  {"type":"model","id":"claude-offset","display_name":"Offset Model",
                   "created_at":"2024-11-04T12:34:56+00:00"}
                ],"has_more":false}
                """);
        Path local = writeLocal(dir, """
                {
                  "api_keys": { "relay": "sk-test-anthropic" },
                  "providers": [
                    { "id": "relay", "name": "Anthropic relay", "api_format": "anthropic",
                      "base_url": "%s/v1",
                      "headers": { "anthropic-version": "2023-06-01" } }
                  ]
                }
                """.formatted(server.baseUrl()));
        ProviderManager manager = ProviderManager.load(local);

        List<ModelInfo> models = manager.listModels("relay");
        assertEquals(2, models.size());
        assertEquals("claude-sonnet-4-20250514", models.get(0).id());
        assertEquals("Claude Sonnet 4", models.get(0).displayName());
        assertEquals(Instant.parse("2025-05-14T00:00:00Z").toEpochMilli(), models.get(0).createdAtMillis());
        // ISO-8601 with an offset (not "Z") parses through the OffsetDateTime fallback
        assertEquals(OffsetDateTime.parse("2024-11-04T12:34:56+00:00").toInstant().toEpochMilli(),
                models.get(1).createdAtMillis());

        // anthropic auth: x-api-key (raw) + anthropic-version; no Authorization header
        assertEquals("/v1/models", server.path(0));
        assertEquals("sk-test-anthropic", server.header(0, "x-api-key"));
        assertEquals("2023-06-01", server.header(0, "anthropic-version"));
        assertNull(server.header(0, "Authorization"));
    }

    // ── Errors ──────────────────────────────────────────────

    @Test
    void missingApiKeyFailsWithEnvHint(@TempDir Path dir) throws Exception {
        FakeServer server = newServer("{}");
        String exoticEnv = "PROVIDER_MANAGER_TEST_NONEXISTENT_KEY_" + System.nanoTime();
        Path local = writeLocal(dir, """
                {
                  "providers": [ { "id": "openai", "base_url": "%s/v1",
                                   "api_key_env": "%s" } ]
                }
                """.formatted(server.baseUrl(), exoticEnv));
        ProviderManager manager = ProviderManager.load(local);
        ProviderException e = assertThrows(ProviderException.class, () -> manager.listModels("openai"));
        assertTrue(e.getMessage().contains(exoticEnv), e.getMessage());
        assertEquals(0, server.calls.get()); // no HTTP call was attempted
    }

    @Test
    void httpErrorSurfacesStatusCodeAndBody(@TempDir Path dir) throws Exception {
        FakeServer server = newServer("{\"error\":{\"message\":\"invalid api key\"}}");
        server.status = 401;
        Path local = writeLocal(dir, """
                {
                  "api_keys": { "openai": "sk-wrong" },
                  "providers": [ { "id": "openai", "base_url": "%s/v1" } ]
                }
                """.formatted(server.baseUrl()));
        ProviderManager manager = ProviderManager.load(local);
        ProviderException e = assertThrows(ProviderException.class, () -> manager.listModels("openai"));
        assertEquals(401, e.statusCode());
        assertEquals("openai", e.providerId());
        assertTrue(e.getMessage().contains("HTTP 401"), e.getMessage());
    }

    @Test
    void unknownProviderFails() {
        ProviderManager manager = ProviderManager.loadDefaults();
        ProviderException e = assertThrows(ProviderException.class, () -> manager.require("no-such-provider"));
        assertTrue(e.getMessage().contains("Unknown provider"), e.getMessage());
        assertTrue(e.getMessage().contains("openai"), "known ids should be listed: " + e.getMessage());
    }

    // ── URL composition and config validation ───────────────

    @Test
    void endpointUrlsJoinWithoutDuplicateSlashes() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "edge");
        entry.put("name", "Edge");
        entry.put("api_format", "openai");
        entry.put("base_url", "https://api.example.com/v1/");  // trailing slash
        Provider p = Provider.fromJson(entry, "test");
        assertEquals("https://api.example.com/v1/models", p.modelsUrl());
        assertEquals("https://api.example.com/v1/chat/completions", p.chatCompletionsUrl());

        // missing api_format / base_url are rejected
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("id", "bad");
        assertThrows(IllegalArgumentException.class, () -> Provider.fromJson(bad, "test"));
        bad.put("api_format", "websocket");
        bad.put("base_url", "http://x");
        assertThrows(IllegalArgumentException.class, () -> Provider.fromJson(bad, "test"));
    }

    @Test
    void missingLocalFileFails() {
        ProviderException e = assertThrows(ProviderException.class,
                () -> ProviderManager.load(Path.of("/no/such/providers.json")));
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
    }
}
