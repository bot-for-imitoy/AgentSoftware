package com.agent.software.llm;

import com.agent.software.store.ConfigStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * End-to-end tests of the retry ordering across several concurrent clients of one endpoint: once
 * the endpoint answers 429, a request that has retried fewer times must wait until the request that
 * has retried more often has finished, and only then take its turn.
 */
class LLMRetryPriorityTest {

    /**
     * Fake chat/completions endpoint that can throttle the first N calls and hold a call open, so a
     * test can observe the queue while one attempt is genuinely in flight. Requires a thread per
     * request (the default executor would serialize the requests in the server itself and hide what
     * the client-side arbiter is doing).
     */
    private static final class GatedServer implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<String> served = new CopyOnWriteArrayList<>();   // caller tags, in completion order
        final CountDownLatch holding = new CountDownLatch(1);
        final CountDownLatch releaseHeld = new CountDownLatch(1);
        /** The first N calls answer 429 (HTTP Too Many Requests). */
        volatile int throttleCalls;
        /** Hold this call number open until {@link #releaseHeld}. */
        volatile int holdCall;
        /** Hold every call open until {@link #releaseHeld}. */
        volatile boolean holdAll;

        GatedServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        private void handle(HttpExchange ex) throws IOException {
            int n = calls.incrementAndGet();
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            String who = ex.getRequestHeaders().getFirst("Authorization");
            who = who == null ? "?" : who.replace("Bearer ", "");
            try {
                if (n <= throttleCalls) {
                    respond(ex, 429);
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
                respond(ex, 200);
                served.add(who);
            } finally {
                inFlight.decrementAndGet();
            }
        }

        private void respond(HttpExchange ex, int status) throws IOException {
            String body = status == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"total_tokens\":3}}"
                    : "{\"error\":{\"message\":\"rate limited\"}}";
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
            releaseHeld.countDown();
            server.stop(0);
        }
    }

    private final List<GatedServer> servers = new ArrayList<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void cleanup() {
        pool.shutdownNow();
        for (GatedServer s : servers) {
            s.close();
        }
        servers.clear();
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
        fail("timed out waiting for: " + what);
    }

    private GatedServer gated() throws IOException {
        GatedServer s = new GatedServer();
        servers.add(s);
        return s;
    }

    /** One client per caller; the tag doubles as the API key so the fake server can tell them apart. */
    private OpenAICompatLLM client(GatedServer s, RetryArbiter arbiter, String tag) {
        OpenAICompatLLM llm = new OpenAICompatLLM(tag, s.baseUrl(), null, tag,
                new ConfigStore(), Map.of(), Map.of());
        llm.retryDelay = 0.15;   // short backoff, so the tests stay fast
        llm.retryMax = 5;
        llm.setRetryArbiter(arbiter);
        return llm;
    }

    // ── A fresh request yields to the request that is already retrying ──

    @Test
    void testFreshRequestWaitsUntilTheMoreRetriedRequestFinished() throws Exception {
        GatedServer s = gated();
        RetryArbiter arbiter = new RetryArbiter("e2e-priority");
        s.throttleCalls = 1;   // call #1 → 429, the endpoint becomes congested
        s.holdCall = 2;        // client A's retry is held open, so A holds the only slot

        OpenAICompatLLM a = client(s, arbiter, "A");
        OpenAICompatLLM b = client(s, arbiter, "B");

        Future<LLM.ChatResponse> fa = pool.submit(() -> a.chat("s", "u", 0.7, 8));
        assertTrue(s.holding.await(5, TimeUnit.SECONDS), "client A's retry should reach the endpoint");
        assertTrue(arbiter.isCongested(), "a 429 must put the endpoint into congested mode");

        // B has retried 0 times, A is on its first retry — B must be blocked until A is done
        Future<LLM.ChatResponse> fb = pool.submit(() -> b.chat("s", "u", 0.7, 8));
        awaitTrue("client B parked behind A", () -> arbiter.waitingCount() == 1);
        Thread.sleep(250);
        assertEquals(2, s.calls.get(), "a lower retry count must not reach the endpoint while A is in flight");
        assertFalse(fb.isDone());
        assertEquals(0, arbiter.highestWaitingRetries());

        s.releaseHeld.countDown();
        assertEquals("ok", fa.get(5, TimeUnit.SECONDS).text);      // the more-retried request finishes first
        assertEquals("ok", fb.get(5, TimeUnit.SECONDS).text);      // then the rest follow in turn
        assertEquals(List.of("A", "B"), s.served);
        assertEquals(3, s.calls.get());
        assertFalse(arbiter.isCongested(), "the endpoint is reopened once a success meets an empty queue");
        assertEquals(0, arbiter.waitingCount());
    }

    // ── The ordering must not slow a healthy endpoint down ──

    @Test
    void testHealthyEndpointStillServesRequestsInParallel() throws Exception {
        GatedServer s = gated();
        RetryArbiter arbiter = new RetryArbiter("e2e-parallel");
        s.holdAll = true;      // both calls stay open until the test releases them

        OpenAICompatLLM a = client(s, arbiter, "A");
        OpenAICompatLLM b = client(s, arbiter, "B");
        Future<LLM.ChatResponse> fa = pool.submit(() -> a.chat("s", "u", 0.7, 8));
        Future<LLM.ChatResponse> fb = pool.submit(() -> b.chat("s", "u", 0.7, 8));

        // no 429 has been seen: both requests must reach the endpoint at the same time
        awaitTrue("both requests in flight at once", () -> s.inFlight.get() == 2);
        s.releaseHeld.countDown();

        assertEquals("ok", fa.get(5, TimeUnit.SECONDS).text);
        assertEquals("ok", fb.get(5, TimeUnit.SECONDS).text);
        assertEquals(2, s.maxInFlight.get());
        assertEquals(0, arbiter.waitingCount());
        assertFalse(arbiter.isCongested());
    }

    // ── Parking must not survive a system pause ──

    @Test
    void testParkedRequestAbortsWhenTheSystemPauses() throws Exception {
        GatedServer s = gated();
        RetryArbiter arbiter = new RetryArbiter("e2e-pause");
        s.throttleCalls = 1;
        s.holdCall = 2;

        OpenAICompatLLM a = client(s, arbiter, "A");
        OpenAICompatLLM b = client(s, arbiter, "B");
        AtomicBoolean paused = new AtomicBoolean(false);
        b.setPauseGate(paused::get);

        Future<LLM.ChatResponse> fa = pool.submit(() -> a.chat("s", "u", 0.7, 8));
        assertTrue(s.holding.await(5, TimeUnit.SECONDS));
        Future<LLM.ChatResponse> fb = pool.submit(() -> b.chat("s", "u", 0.7, 8));
        awaitTrue("client B parked behind A", () -> arbiter.waitingCount() == 1);

        paused.set(true);      // the system pauses while B is still waiting its turn
        LLM.ChatResponse rb = fb.get(5, TimeUnit.SECONDS);
        assertTrue(rb.text.contains("system paused"), rb.text);
        assertEquals(0, arbiter.waitingCount(), "an aborted request must leave the queue");
        assertEquals(2, s.calls.get(), "an aborted request must never reach the endpoint");

        s.releaseHeld.countDown();
        assertEquals("ok", fa.get(5, TimeUnit.SECONDS).text);

        // the queue still works afterwards
        OpenAICompatLLM c = client(s, arbiter, "C");
        assertEquals("ok", pool.submit(() -> c.chat("s", "u", 0.7, 8)).get(5, TimeUnit.SECONDS).text);
    }
}
