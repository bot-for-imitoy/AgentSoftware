package com.agent.software.llm;

import com.agent.software.store.ConfigStore;
import com.agent.software.utils.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Embedding 客户端：地址规范化、响应解析，以及对着本地假网关的真实 HTTP 往返。 */
class OpenAICompatEmbeddingTest {

    @Test
    void normalizesBaseUrlLikeTheLlmClient() {
        assertEquals("https://api.openai.com/v1", OpenAICompatEmbedding.normalizeBaseUrl("https://api.openai.com"));
        assertEquals("https://api.openai.com/v1", OpenAICompatEmbedding.normalizeBaseUrl("https://api.openai.com/"));
        assertEquals("https://gw.example/v1", OpenAICompatEmbedding.normalizeBaseUrl("https://gw.example/v1"));
        assertEquals("http://127.0.0.1:8080/v1",
                OpenAICompatEmbedding.normalizeBaseUrl("http://127.0.0.1:8080"));
    }

    @Test
    void parsesTheOpenAiEmbeddingShape() {
        double[] v = OpenAICompatEmbedding.parseEmbedding(
                "{\"data\":[{\"embedding\":[0.5,-1,2],\"index\":0}],\"model\":\"m\"}");
        assertArrayEquals(new double[]{0.5, -1, 2}, v, 1e-9);

        // 有的网关把数字给成字符串
        assertArrayEquals(new double[]{1, 2}, OpenAICompatEmbedding.parseEmbedding(
                "{\"data\":[{\"embedding\":[\"1\",2.0]}]}"), 1e-9);

        assertArrayEquals(new double[0], OpenAICompatEmbedding.parseEmbedding("{}"));
        assertArrayEquals(new double[0], OpenAICompatEmbedding.parseEmbedding("{\"data\":[]}"));
        assertArrayEquals(new double[0], OpenAICompatEmbedding.parseEmbedding("not json"));
        assertArrayEquals(new double[0], OpenAICompatEmbedding.parseEmbedding(null));
    }

    @Test
    void postsToTheEmbeddingsEndpointAndParsesTheVector(@TempDir Path dir) throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            path.set(ex.getRequestURI().getPath());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            respond(ex, 200, "{\"data\":[{\"embedding\":[0.1,0.2,0.3]}]}");
        });
        server.start();
        try {
            OpenAICompatEmbedding embedding = embeddingFor(dir, server, "secret", "test-embed");

            assertEquals("test-embed", embedding.getModel());
            assertEquals("http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    embedding.getEndpoint());
            assertTrue(embedding.isConfigured());

            double[] v = embedding.embed("hello world");
            assertArrayEquals(new double[]{0.1, 0.2, 0.3}, v, 1e-9);
            assertEquals(3, embedding.dimension());
            assertEquals("/v1/embeddings", path.get());
            assertEquals("Bearer secret", auth.get());
            assertTrue(body.get().contains("\"model\":\"test-embed\""), body.get());
            assertTrue(body.get().contains("hello world"), body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void a4xxDisablesFurtherAttemptsSoItCannotSlowEveryMessageDown(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            hits.incrementAndGet();
            respond(ex, 404, "{\"error\":{\"message\":\"model not found\"}}");
        });
        server.start();
        try {
            OpenAICompatEmbedding embedding = embeddingFor(dir, server, "k", "nope");
            assertArrayEquals(new double[0], embedding.embed("a"));
            assertArrayEquals(new double[0], embedding.embed("b"));
            assertArrayEquals(new double[0], embedding.embed("c"));
            assertEquals(1, hits.get(), "4xx 是配置错误，熔断后再也不撞墙");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blankTextAndMissingModelNeverHitTheNetwork(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            hits.incrementAndGet();
            respond(ex, 200, "{\"data\":[{\"embedding\":[1]}]}");
        });
        server.start();
        try {
            OpenAICompatEmbedding embedding = embeddingFor(dir, server, "k", "test-embed");
            assertArrayEquals(new double[0], embedding.embed("   "));
            assertEquals(0, hits.get(), "空文本没必要请求");

            ConfigStore blank = new ConfigStore(dir.resolve("empty.json"));
            OpenAICompatEmbedding unconfigured = new OpenAICompatEmbedding("k", null, blank);
            assertFalse(unconfigured.isConfigured(), "没有 embedding.model 就是没配置");
            assertArrayEquals(new double[0], unconfigured.embed("hello"));
            assertEquals(0, hits.get());
        } finally {
            server.stop(0);
        }
    }

    private static OpenAICompatEmbedding embeddingFor(Path dir, HttpServer server,
                                                     String apiKey, String model) {
        Path cfg = dir.resolve("config.json");
        Json.writeFile(cfg, Map.of("embedding", Map.of(
                "base_url", "http://127.0.0.1:" + server.getAddress().getPort(),
                "model", model)));
        return new OpenAICompatEmbedding(apiKey, null, new ConfigStore(cfg));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws java.io.IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }
}
