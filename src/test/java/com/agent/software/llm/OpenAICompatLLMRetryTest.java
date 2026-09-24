package com.agent.software.llm;

import com.agent.software.llm.context.Context;
import com.agent.software.store.ConfigStore;
import com.agent.software.utils.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LLM 请求重试策略：最多 200 次、退避线性但有上限、被打断就立刻收手。 */
class OpenAICompatLLMRetryTest {

    @Test
    void backoffGrowsLinearlyButIsCapped() {
        assertEquals(1_000L, OpenAICompatLLM.backoffMillis(1));
        assertEquals(2_000L, OpenAICompatLLM.backoffMillis(2));
        assertEquals(29_000L, OpenAICompatLLM.backoffMillis(29));
        assertEquals(30_000L, OpenAICompatLLM.backoffMillis(30));
        assertEquals(30_000L, OpenAICompatLLM.backoffMillis(200), "第 200 次不该睡 200 秒");
    }

    /** 网关连吐 3 个 503，第 4 次成功 —— 请求要能扛过去。 */
    @Test
    void keepsRetryingUntilTheGatewayRecovers(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            int n = hits.incrementAndGet();
            if (n <= 3) {
                respond(ex, 503, "{\"error\":{\"message\":\"Service temporarily unavailable\"}}");
            } else {
                respond(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}");
            }
        });
        server.start();
        try {
            OpenAICompatLLM llm = llmFor(dir, server);
            Response r = llm.request();
            assertEquals("ok", r.text);
            assertEquals(4, hits.get(), "应重试到第 4 次才成功");
        } finally {
            server.stop(0);
        }
    }

    /** 角色被 stop()（或下班打断）时，不能把剩下的 199 次重试打完再去打网关。 */
    @Test
    void stopsRetryingWhenTheThreadIsInterrupted(@TempDir Path dir) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            hits.incrementAndGet();
            respond(ex, 503, "{\"error\":{\"message\":\"still down\"}}");
        });
        server.start();
        try {
            OpenAICompatLLM llm = llmFor(dir, server);
            CompletableFuture<Response> call = new CompletableFuture<>();
            Thread worker = new Thread(() -> call.complete(llm.request()), "llm-retry-test");
            worker.start();
            Thread.sleep(300);
            worker.interrupt();

            Response r = call.get(3, TimeUnit.SECONDS);
            assertTrue(r.text.contains("interrupted"), r.text);
            assertTrue(hits.get() <= 3, "打断后不该继续重试，实际打了 " + hits.get() + " 次");
        } finally {
            server.stop(0);
        }
    }

    private static OpenAICompatLLM llmFor(Path dir, HttpServer server) {
        Path cfg = dir.resolve("config.json");
        Json.writeFile(cfg, Map.of("llm", Map.of(
                "base_url", "http://127.0.0.1:" + server.getAddress().getPort(),
                "model", "test-model")));
        OpenAICompatLLM llm = new OpenAICompatLLM("test-key", null, new ConfigStore(cfg));
        llm.setContext(new Context());
        return llm;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body)
            throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }
}
