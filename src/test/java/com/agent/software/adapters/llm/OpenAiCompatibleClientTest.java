package com.agent.software.adapters.llm;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.ports.ChatMessage;
import com.agent.software.ports.LlmPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolSpec;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {

    private HttpServer server;
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile IntSupplier statusSupplier = () -> 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            calls.incrementAndGet();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusSupplier.getAsInt(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenAiCompatibleClient client(OpenAiCompatibleClient.RetryPolicy policy) {
        OpenAiCompatibleClient.Endpoint endpoint = new OpenAiCompatibleClient.Endpoint(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "/chat/completions", "sk-test", "Authorization", "Bearer", Map.of(), "test-model");
        return new OpenAiCompatibleClient(endpoint, policy, null);
    }

    private static OpenAiCompatibleClient.RetryPolicy fastRetry() {
        return new OpenAiCompatibleClient.RetryPolicy(4, 0.01, 5);
    }

    @Test
    void chatParsesTextReasoningAndTokens() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"hello\","
                + "\"reasoning_content\":\"why\"}}],\"usage\":{\"total_tokens\":42}}";

        LlmPort.ChatReply reply = client(fastRetry()).chat(new LlmPort.ChatRequest("system", "user"));

        assertEquals("hello", reply.text());
        assertEquals("why", reply.reasoning());
        assertEquals(42, reply.tokens());
        assertEquals("Bearer sk-test", authHeaders.get(0));
        assertTrue(requestBodies.get(0).contains("\"model\":\"test-model\""));
    }

    @Test
    void chatWithToolsParsesToolCallsAndSendsSchemas() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":[{\"id\":\"c1\","
                + "\"type\":\"function\",\"function\":{\"name\":\"echo\",\"arguments\":\"{\\\"text\\\":\\\"hi\\\"}\"}}]}}],"
                + "\"usage\":{\"total_tokens\":7}}";
        ToolSpec spec = new ToolSpec("echo", "echo text",
                JsonSchema.builder().required("text", JsonSchema.Property.string("text")).build());

        LlmPort.ToolReply reply = client(fastRetry()).chatWithTools(new LlmPort.ToolRequest(
                List.of(ChatMessage.user("say hi")), List.of(spec), 0.7, null));

        assertEquals(1, reply.toolCalls().size());
        ToolCall call = reply.toolCalls().get(0);
        assertEquals("c1", call.id());
        assertEquals("echo", call.name());
        assertEquals("hi", call.arguments().str("text", ""));
        assertEquals(7, reply.tokens());
        assertTrue(requestBodies.get(0).contains("\"tool_choice\":\"auto\""));
        assertTrue(requestBodies.get(0).contains("\"required\":[\"text\"]"));
    }

    @Test
    void retriesOnServerErrorThenSucceeds() {
        statusSupplier = () -> calls.get() <= 1 ? 500 : 200;
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"recovered\"}}]}";

        LlmPort.ChatReply reply = client(new OpenAiCompatibleClient.RetryPolicy(3, 0.01, 5))
                .chat(new LlmPort.ChatRequest("s", "u"));

        assertEquals("recovered", reply.text());
        assertEquals(2, calls.get(), "a 5xx must be retried");
    }

    @Test
    void clientErrorFailsWithoutRetry() {
        statusSupplier = () -> 400;
        responseBody = "{\"error\":\"bad request\"}";

        LlmPort.ChatReply reply = client(fastRetry()).chat(new LlmPort.ChatRequest("s", "u"));

        assertTrue(reply.failed());
        assertEquals(1, calls.get(), "a 4xx must not be retried");
    }

    @Test
    void insufficientBalanceTriggersAutoPauseHook() {
        statusSupplier = () -> 402;
        responseBody = "{\"error\":\"Insufficient Balance\"}";
        OpenAiCompatibleClient client = client(fastRetry());
        AtomicReference<String> reason = new AtomicReference<>();
        client.setOnInsufficientBalance(reason::set);

        LlmPort.ChatReply reply = client.chat(new LlmPort.ChatRequest("s", "u"));

        assertTrue(reply.failed());
        assertEquals(1, calls.get(), "balance exhaustion must not be retried");
        assertTrue(reason.get().contains("insufficient"));
    }

    @Test
    void pausedGateAbortsWithoutCallingTheEndpoint() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"nope\"}}]}";
        OpenAiCompatibleClient client = client(fastRetry());
        client.setPausedGate(() -> true);

        LlmPort.ChatReply reply = client.chat(new LlmPort.ChatRequest("s", "u"));

        assertTrue(reply.failed());
        assertEquals(0, calls.get());
    }
}
