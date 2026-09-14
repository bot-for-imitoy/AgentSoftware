package com.agent.software.adapters.llm;

import com.agent.software.domain.Payload;
import com.agent.software.llm.RetryArbiter;
import com.agent.software.ports.ChatMessage;
import com.agent.software.ports.LlmPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolSpec;
import com.agent.software.utils.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * OpenAI-compatible HTTP client implementing {@link LlmPort}.
 *
 * <p>Replaces {@code OpenAICompatLLM}: configuration arrives as an immutable
 * {@link Endpoint} (resolved from the provider catalog by the composition root)
 * instead of being read from {@code OPENAI_*} environment variables, and the URL
 * is {@code baseUrl + chatPath} with no hard-coded {@code /v1}.
 *
 * <p>Retries preserve the documented behaviour: 429/5xx and timeouts retry after
 * a fixed delay through a shared {@link RetryArbiter}, other 4xx fail
 * immediately, and an exhausted account balance triggers the auto-pause hook
 * instead of retrying. Requests are non-streaming.
 */
public final class OpenAiCompatibleClient implements LlmPort {

    /** Everything needed to reach one provider endpoint. */
    public record Endpoint(String baseUrl, String chatPath, String apiKey, String authHeader,
                           String authScheme, Map<String, String> extraHeaders, String model) {
        public Endpoint {
            baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl);
            chatPath = chatPath == null || chatPath.isBlank()
                    ? "/chat/completions" : ensureLeadingSlash(chatPath);
            apiKey = apiKey == null ? "" : apiKey;
            authHeader = authHeader == null || authHeader.isBlank() ? "Authorization" : authHeader;
            extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
            model = model == null ? "" : model;
        }

        public String chatUrl() {
            return baseUrl + chatPath;
        }
    }

    /** Retry/timeout knobs, mapped from {@code AppConfig.Llm.Retry}. */
    public record RetryPolicy(int maxAttempts, double delaySeconds, int timeoutSeconds) {
        public RetryPolicy {
            maxAttempts = Math.max(1, maxAttempts);
            delaySeconds = Math.max(0, delaySeconds);
            timeoutSeconds = Math.max(1, timeoutSeconds);
        }

        public static RetryPolicy defaults() {
            return new RetryPolicy(200, 10.0, 120);
        }
    }

    private static final List<String> INSUFFICIENT_BALANCE_MARKERS = List.of(
            "insufficient_quota", "insufficient quota", "insufficient balance",
            "exceeded your current quota", "out of credits", "not enough balance",
            "no enough balance", "payment required", "quota exceeded",
            "\u4f59\u989d\u4e0d\u8db3", "\u8d26\u6237\u4f59\u989d", "\u4f59\u989d\u5df2\u7528\u5b8c",
            "\u6b20\u8d39", "\u989d\u5ea6\u4e0d\u8db3");

    private final Endpoint endpoint;
    private final RetryPolicy retry;
    private final RetryArbiter arbiter;

    private volatile Consumer<String> onInsufficientBalance = reason -> {
    };
    private volatile BooleanSupplier pausedGate = () -> false;
    private volatile String lastError = "";

    public OpenAiCompatibleClient(Endpoint endpoint, RetryPolicy retry, RetryArbiter arbiter) {
        this.endpoint = endpoint;
        this.retry = retry == null ? RetryPolicy.defaults() : retry;
        this.arbiter = arbiter;
    }

    public Endpoint endpoint() {
        return endpoint;
    }

    /** Invoked once when the account balance/quota is exhausted. */
    public void setOnInsufficientBalance(Consumer<String> listener) {
        this.onInsufficientBalance = listener == null ? reason -> {
        } : listener;
    }

    /** While this returns true, no new attempt is made. */
    public void setPausedGate(BooleanSupplier gate) {
        this.pausedGate = gate == null ? () -> false : gate;
    }

    public String lastError() {
        return lastError;
    }

    // ── LlmPort ────────────────────────────────────────────────────────

    @Override
    public ChatReply chat(ChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (!request.system().isBlank()) {
            messages.add(message("system", request.system()));
        }
        messages.add(message("user", request.user()));

        Map<String, Object> data = postWithRetry(
                payload(messages, request.temperature(), Math.max(1, request.maxTokens())));
        if (data == null) {
            return new ChatReply(API_ERROR_PREFIX + " " + lastError, "", 0);
        }
        Map<String, Object> msg = choiceMessage(data);
        String content = str(msg.get("content"));
        String reasoning = str(msg.get("reasoning_content"));
        if (reasoning.isEmpty()) {
            reasoning = str(msg.get("reasoning"));
        }
        if (content.isEmpty() && !reasoning.isEmpty()) {
            content = reasoning;
        }
        return new ChatReply(content, reasoning, tokensOf(data));
    }

    @Override
    public ChatReply summarize(String text, int maxTokens) {
        String system = "You are a professional assistant in charge of writing work summaries. "
                + "Summarize the following content concisely, extracting key decisions, action items, "
                + "and noteworthy low-priority events.";
        return chat(new ChatRequest(system, "Please summarize today's work log:\n" + (text == null ? "" : text),
                0.3, Math.max(1, maxTokens)));
    }

    @Override
    public ToolReply chatWithTools(ToolRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : request.messages()) {
            messages.add(toWire(m));
        }
        Map<String, Object> body = payload(messages, request.temperature(), request.maxTokens());
        body.put("tools", toolsOf(request.tools()));
        body.put("tool_choice", "auto");

        Map<String, Object> data = postWithRetry(body);
        if (data == null) {
            return new ToolReply(API_ERROR_PREFIX + " " + lastError, "", List.of(), 0);
        }
        Map<String, Object> msg = choiceMessage(data);
        String content = str(msg.get("content"));
        String reasoning = str(msg.get("reasoning_content"));
        if (reasoning.isEmpty()) {
            reasoning = str(msg.get("reasoning"));
        }
        List<ToolCall> calls = parseToolCalls(msg.get("tool_calls"));
        if (content.isEmpty() && calls.isEmpty() && !reasoning.isEmpty()) {
            content = reasoning;
        }
        return new ToolReply(content, reasoning, calls, tokensOf(data));
    }

    // ── HTTP + retry ───────────────────────────────────────────────────

    private Map<String, Object> postWithRetry(Map<String, Object> body) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(retry.timeoutSeconds()))
                .build();
        for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
            if (pausedGate.getAsBoolean()) {
                lastError = "aborted: system paused";
                return null;
            }
            int retries = attempt - 1;
            RetryArbiter.Slot slot = arbiter == null ? null
                    : arbiter.acquire(retries, RetryArbiter.POLL_MILLIS, () -> !pausedGate.getAsBoolean());
            if (arbiter != null && slot == null) {
                lastError = pausedGate.getAsBoolean() ? "aborted: system paused" : "aborted: interrupted";
                return null;
            }
            boolean succeeded = false;
            try {
                HttpResponse<String> response = client.send(buildRequest(body),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                String responseBody = response.body() == null ? "" : response.body();

                if (isInsufficientBalance(status, responseBody)) {
                    lastError = "insufficient balance/quota (HTTP " + status + ")";
                    notifyInsufficientBalance(status);
                    return null;
                }
                if (status == 429 || status >= 500) {
                    lastError = "HTTP " + status;
                    if (arbiter != null) {
                        arbiter.throttled(lastError);
                    }
                } else if (status >= 400) {
                    lastError = "HTTP " + status + ": " + truncate(responseBody, 200);
                    return null;
                } else {
                    succeeded = true;
                    return Json.parseObject(responseBody);
                }
            } catch (HttpTimeoutException e) {
                lastError = "timeout";
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                lastError = e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 120);
            } finally {
                if (slot != null) {
                    slot.release(succeeded);
                }
            }
            sleep(retry.delaySeconds());
        }
        lastError = "retried " + retry.maxAttempts() + " times and still failed: " + lastError;
        return null;
    }

    private HttpRequest buildRequest(Map<String, Object> body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint.chatUrl()))
                .timeout(Duration.ofSeconds(retry.timeoutSeconds()))
                .header("Content-Type", "application/json");
        if (!endpoint.apiKey().isBlank()) {
            String scheme = endpoint.authScheme();
            String value = scheme == null || scheme.isBlank()
                    ? endpoint.apiKey() : scheme + " " + endpoint.apiKey();
            builder.header(endpoint.authHeader(), value);
        }
        endpoint.extraHeaders().forEach(builder::header);
        return builder.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
    }

    // ── payload / parsing ──────────────────────────────────────────────

    private Map<String, Object> payload(List<Map<String, Object>> messages, double temperature, Integer maxTokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model());
        body.put("messages", messages);
        body.put("temperature", temperature);
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        return body;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static Map<String, Object> toWire(ChatMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", m.role());
        if (!m.toolCalls().isEmpty()) {
            out.put("content", m.content().isEmpty() ? null : m.content());
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : m.toolCalls()) {
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", call.name());
                fn.put("arguments", Json.stringify(call.arguments().asMap()));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", call.id());
                entry.put("type", "function");
                entry.put("function", fn);
                calls.add(entry);
            }
            out.put("tool_calls", calls);
        } else if ("tool".equals(m.role())) {
            out.put("content", m.content());
            out.put("tool_call_id", m.toolCallId());
        } else {
            out.put("content", m.content());
        }
        return out;
    }

    private static List<Map<String, Object>> toolsOf(List<ToolSpec> specs) {
        List<Map<String, Object>> out = new ArrayList<>(specs.size());
        for (ToolSpec spec : specs) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", spec.name());
            fn.put("description", spec.description());
            fn.put("parameters", spec.schema().toMap());
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", fn);
            out.add(wrapper);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> choiceMessage(Map<String, Object> data) {
        Object choices = data.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            Object message = ((Map<String, Object>) first).get("message");
            if (message instanceof Map<?, ?> m) {
                return (Map<String, Object>) m;
            }
        }
        return Map.of();
    }

    private static int tokensOf(Map<String, Object> data) {
        Object usage = data.get("usage");
        if (usage instanceof Map<?, ?> u && u.get("total_tokens") instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static List<ToolCall> parseToolCalls(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ToolCall> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String id = str(entry.get("id"));
            Object fnRaw = entry.get("function");
            String name = "";
            Payload arguments = Payload.empty();
            if (fnRaw instanceof Map<?, ?> fn) {
                name = str(fn.get("name"));
                Object argsRaw = fn.get("arguments");
                if (argsRaw instanceof String s && !s.isBlank()) {
                    try {
                        Object parsed = Json.parse(s);
                        if (parsed instanceof Map<?, ?> map) {
                            arguments = Payload.of((Map<String, Object>) map);
                        }
                    } catch (IOException ignored) {
                        arguments = Payload.empty();
                    }
                } else if (argsRaw instanceof Map<?, ?> map) {
                    arguments = Payload.of((Map<String, Object>) map);
                }
            }
            if (!name.isBlank()) {
                out.add(new ToolCall(id, name, arguments));
            }
        }
        return out;
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static boolean isInsufficientBalance(int status, String body) {
        if (status == 402) {
            return true;
        }
        if (body == null || body.isEmpty()) {
            return false;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        for (String marker : INSUFFICIENT_BALANCE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private void notifyInsufficientBalance(int status) {
        try {
            onInsufficientBalance.accept("API reported insufficient balance/quota (HTTP " + status + ")");
        } catch (RuntimeException ignored) {
            // a broken listener must not break the client
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static String stripTrailingSlash(String s) {
        String out = s;
        while (out.length() > 1 && out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static String ensureLeadingSlash(String s) {
        return s.startsWith("/") ? s : "/" + s;
    }

    private void sleep(double seconds) {
        if (seconds <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + (long) (seconds * 1000);
        while (System.currentTimeMillis() < deadline) {
            if (pausedGate.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(Math.min(200, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
