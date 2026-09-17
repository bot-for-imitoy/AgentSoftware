package com.agent.software.llm;

import com.agent.software.infra.config.AppConfig;
import com.agent.software.llm.LlmClient.ChatReply;
import com.agent.software.llm.LlmClient.ChatRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 同一个 endpoint 上多个并发客户端的端到端重试排序测试：endpoint 一旦回 429，
 * 重试次数少的请求必须等重试次数多的请求跑完才能轮到自己。
 *
 * <p>迁移自 master 的 {@code LLMRetryPriorityTest}，适配新 API：
 * <ul>
 *   <li>仲裁器通过 {@link OpenAiClient} 构造器注入，而不是 {@code setRetryArbiter}；</li>
 *   <li>不再有 {@code waitingCount()} 内省方法，改用"暂停门被轮询的次数"来判定
 *       某个请求确实已经停在队列里（{@code acquire} 只在入队未获准时才回调 keepWaiting，
 *       而 keepWaiting 内部会查询暂停门）；</li>
 *   <li>暂停语义变化：排队者遇到暂停会退出队列并在暂停门处等待恢复，而不是立即返回
 *       {@code "aborted: system paused"}。</li>
 * </ul>
 */
class OpenAiClientRetryPriorityTest {

    private static final ChatRequest CHAT = new ChatRequest("s", "u", 0.7, 8);

    /**
     * 假 chat/completions 服务：可以让前 N 次调用回 429，也可以把某次调用挂住，
     * 这样测试能在"确实有请求在途"时观察队列。每个请求必须一个线程
     * （默认执行器会把请求在服务端串行化，掩盖客户端仲裁器的行为）。
     */
    private static final class GatedServer implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        /** 调用方 tag，按完成顺序记录。 */
        final List<String> served = new CopyOnWriteArrayList<>();
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch releaseHeld = new CountDownLatch(1);
        /** 前 throttleCalls 次调用回 429。 */
        volatile int throttleCalls;
        /** 把第 holdCall 次调用挂住，直到 {@link #releaseHeld}。 */
        volatile int holdCall;
        /** 挂住每一次调用，直到 {@link #releaseHeld}。 */
        volatile boolean holdAll;

        private final ExecutorService executor = Executors.newCachedThreadPool();

        GatedServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            int n = calls.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            String who = exchange.getRequestHeaders().getFirst("Authorization");
            who = who == null ? "?" : who.replace("Bearer ", "");
            try {
                if (n <= throttleCalls) {
                    respond(exchange, 429);
                    return;
                }
                if (holdAll || n == holdCall) {
                    holding.countDown();
                    try {
                        releaseHeld.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                respond(exchange, 200);
                served.add(who);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private void respond(HttpExchange exchange, int status) throws IOException {
            String body = status == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"total_tokens\":3}}"
                    : "{\"error\":{\"message\":\"rate limited\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            releaseHeld.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private final List<GatedServer> servers = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @BeforeEach
    void isolateSharedArbiters() {
        RetryArbiter.clearShared();
    }

    @AfterEach
    void cleanup() {
        pool.shutdownNow();
        for (GatedServer server : servers) {
            server.close();
        }
        servers.clear();
        RetryArbiter.clearShared();
    }

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

    private GatedServer gated() throws IOException {
        GatedServer server = new GatedServer();
        servers.add(server);
        return server;
    }

    /** 每个调用方一个客户端；tag 同时充当 API key，便于假服务区分是谁。 */
    private OpenAiClient client(GatedServer server, RetryArbiter arbiter, String tag) {
        ProviderResolver.Endpoint endpoint =
                new ProviderResolver.Endpoint(server.baseUrl() + "/v1", tag, "test-model");
        AppConfig.Llm.Retry retry = new AppConfig.Llm.Retry(5, 0.15, 10);
        return new OpenAiClient(endpoint, retry, arbiter);
    }

    // ── 新鲜请求给正在重试的请求让路 ────────────────────────

    @Test
    void testFreshRequestWaitsUntilTheMoreRetriedRequestFinished() throws Exception {
        GatedServer server = gated();
        RetryArbiter arbiter = RetryArbiter.forEndpoint("e2e-priority");
        server.throttleCalls = 1;   // 第 1 次 → 429，endpoint 进入拥塞
        server.holdCall = 2;        // A 的重试被挂住，于是 A 占着唯一名额

        OpenAiClient a = client(server, arbiter, "A");
        OpenAiClient b = client(server, arbiter, "B");

        Future<ChatReply> fa = pool.submit(() -> a.chat(CHAT));
        assertTrue(server.holding.await(5, TimeUnit.SECONDS), "A 的重试应该已经到达 endpoint");
        assertTrue(arbiter.isCongested(), "一次 429 必须让 endpoint 进入拥塞状态");

        // B 重试 0 次，A 已经在第一次重试上 —— B 必须被挡住直到 A 跑完。
        // 用暂停门被轮询的次数判定 B 已停在队列里（循环顶部一次 + 入队后 keepWaiting 一次）。
        AtomicInteger bGatePolls = new AtomicInteger();
        b.setPausedGate(() -> {
            bGatePolls.incrementAndGet();
            return false;
        });
        Future<ChatReply> fb = pool.submit(() -> b.chat(CHAT));
        awaitTrue("B 已在 A 后面排队", () -> bGatePolls.get() >= 2);
        assertEquals(2, server.calls.get(), "重试次数少的请求不得在 A 在途时到达 endpoint");
        assertFalse(fb.isDone());

        server.releaseHeld.countDown();
        assertEquals("ok", fa.get(5, TimeUnit.SECONDS).text());   // 重试更深的先完成
        assertEquals("ok", fb.get(5, TimeUnit.SECONDS).text());   // 其余的随后按序
        assertEquals(List.of("A", "B"), server.served);
        assertEquals(3, server.calls.get());
        assertFalse(arbiter.isCongested(), "成功且队列为空后 endpoint 重新开放");
    }

    // ── 排序不能让健康 endpoint 变慢 ────────────────────────

    @Test
    void testHealthyEndpointStillServesRequestsInParallel() throws Exception {
        GatedServer server = gated();
        RetryArbiter arbiter = RetryArbiter.forEndpoint("e2e-parallel");
        server.holdAll = true;      // 两个请求都挂住直到测试放行

        OpenAiClient a = client(server, arbiter, "A");
        OpenAiClient b = client(server, arbiter, "B");
        Future<ChatReply> fa = pool.submit(() -> a.chat(CHAT));
        Future<ChatReply> fb = pool.submit(() -> b.chat(CHAT));

        // 没有出现过 429：两个请求必须同时到达 endpoint
        awaitTrue("两个请求同时在途", () -> server.inFlight.get() == 2);
        server.releaseHeld.countDown();

        assertEquals("ok", fa.get(5, TimeUnit.SECONDS).text());
        assertEquals("ok", fb.get(5, TimeUnit.SECONDS).text());
        assertEquals(2, server.maxInFlight.get());
        assertFalse(arbiter.isCongested());
    }

    // ── 系统暂停必须能让排队者退出 ──────────────────────────

    @Test
    void testParkedRequestSendsNothingWhileTheSystemIsPaused() throws Exception {
        GatedServer server = gated();
        RetryArbiter arbiter = RetryArbiter.forEndpoint("e2e-pause");
        server.throttleCalls = 1;
        server.holdCall = 2;

        OpenAiClient a = client(server, arbiter, "A");
        OpenAiClient b = client(server, arbiter, "B");
        AtomicBoolean paused = new AtomicBoolean(false);
        AtomicInteger bGatePolls = new AtomicInteger();
        b.setPausedGate(() -> {
            bGatePolls.incrementAndGet();
            return paused.get();
        });

        Future<ChatReply> fa = pool.submit(() -> a.chat(CHAT));
        assertTrue(server.holding.await(5, TimeUnit.SECONDS));
        Future<ChatReply> fb = pool.submit(() -> b.chat(CHAT));
        awaitTrue("B 已在 A 后面排队", () -> bGatePolls.get() >= 2);

        paused.set(true);      // B 还在等自己的回合时系统被暂停
        awaitTrue("B 已感知暂停", () -> bGatePolls.get() >= 3);
        assertEquals(2, server.calls.get(), "暂停期间排队者绝不能发请求");
        assertFalse(fb.isDone(), "新语义：排队者退出队列并在暂停门处等待恢复");

        server.releaseHeld.countDown();
        assertEquals("ok", fa.get(5, TimeUnit.SECONDS).text());
        assertEquals(2, server.calls.get(), "B 在整个暂停期间都没有发出请求");

        paused.set(false);     // 恢复后 B 才继续
        assertEquals("ok", fb.get(5, TimeUnit.SECONDS).text());
        assertEquals(3, server.calls.get());

        // 队列之后仍然可用
        OpenAiClient c = client(server, arbiter, "C");
        assertEquals("ok", pool.submit(() -> c.chat(CHAT)).get(5, TimeUnit.SECONDS).text());
        assertEquals(4, server.calls.get());
    }
}
