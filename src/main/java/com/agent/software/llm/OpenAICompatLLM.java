package com.agent.software.llm;

import com.agent.software.llm.context.AssistantMessage;
import com.agent.software.llm.context.Context;
import com.agent.software.llm.context.Message;
import com.agent.software.llm.context.ToolMessage;
import com.agent.software.store.ConfigStore;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;
import com.agent.software.utils.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容客户端（DeepSeek / vLLM / Ollama / LM Studio 都走这个）。
 *
 * <p>重试内置于此：区分可重试（429 / 5xx / IO 超时）与不可重试（其它 4xx），
 * 余额/配额问题返回错误文本，由上层决定是否暂停系统。
 */
public class OpenAICompatLLM extends LLM {

    private static final Logger logger = LoggerFactory.getLogger(OpenAICompatLLM.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MILLIS = 1000L;

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public OpenAICompatLLM(String apiKey, String model) {
        this(apiKey, model, null);
    }

    public OpenAICompatLLM(String apiKey, String model, ConfigStore config) {
        this.apiKey = firstNonBlank(apiKey, env("OPENAI_API_KEY"), configString(config, "llm.api_key"));
        this.model = firstNonBlank(model, env("OPENAI_MODEL"), configString(config, "llm.model"), "gpt-4o-mini");
        this.baseUrl = normalizeBaseUrl(stripTrailingSlash(firstNonBlank(
                env("OPENAI_BASE_URL"), configString(config, "llm.base_url"), "https://api.openai.com/v1")));
    }

    /**
     * 规范化 base_url：只给域名（路径为空或 "/"）时自动补 "/v1"。
     * 例如 {@code https://hhcoding.fun} → {@code https://hhcoding.fun/v1}；已经带路径则原样保留。
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                return url + "/v1";
            }
        } catch (IllegalArgumentException ignored) {
        }
        return url;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public String getEndpoint() {
        return baseUrl;
    }

    @Override
    public Response request() {
        Context context = getContext();
        if (context == null) {
            return new Response("API error: context not set", "", List.of(), 0);
        }
        List<Map<String, Object>> messages = buildMessages(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", getTemperature());
        if (getMaxTokens() != null) {
            body.put("max_tokens", getMaxTokens());
        }
        List<Map<String, Object>> toolSpecs = toolSpecs();
        if (!toolSpecs.isEmpty()) {
            body.put("tools", toolSpecs);
        }
        String payload = Json.stringify(body);

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder rb = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .timeout(Duration.ofMinutes(5))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload));
                if (apiKey != null && !apiKey.isBlank()) {
                    rb.header("Authorization", "Bearer " + apiKey);
                }
                HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    return parseResponse(resp.body());
                }
                if (isRetryable(status) && attempt < MAX_ATTEMPTS) {
                    logger.warn("LLM HTTP {} (attempt {}/{}), retrying", status, attempt, MAX_ATTEMPTS);
                    sleep(RETRY_BASE_MILLIS * attempt);
                    continue;
                }
                String detail = resp.body() == null ? "" : resp.body();
                logger.warn("LLM HTTP {} from {}: {}", status, baseUrl, Text.truncate(detail, 300));
                return new Response("API error: HTTP " + status + " " + Text.truncate(detail, 300),
                        "", List.of(), 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Response("API error: interrupted", "", List.of(), 0);
            } catch (Exception e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS) {
                    logger.warn("LLM request failed (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, e.toString());
                    sleep(RETRY_BASE_MILLIS * attempt);
                    continue;
                }
            }
        }
        logger.warn("LLM request failed after {} attempt(s): {}", MAX_ATTEMPTS,
                lastError == null ? "unknown" : lastError.toString());
        return new Response("API error: " + (lastError == null ? "unknown" : lastError.getMessage()),
                "", List.of(), 0);
    }

    private List<Map<String, Object>> buildMessages(Context context) {
        List<Map<String, Object>> out = new ArrayList<>();
        String system = getSystemPrompt();
        if (system != null && !system.isBlank()) {
            out.add(message("system", system, null, null, null));
        }
        for (Message m : context.messages()) {
            if (m instanceof AssistantMessage a) {
                out.add(message("assistant", a.content, a.toolCalls, null, null));
            } else if (m instanceof ToolMessage t) {
                out.add(message("tool", t.content, null, t.toolCallId, t.name));
            } else {
                out.add(message(m.getRole(), m.content, null, null, null));
            }
        }
        return out;
    }

    private static Map<String, Object> message(String role, String content,
                                               List<Map<String, Object>> toolCalls,
                                               String toolCallId, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content == null ? "" : content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            m.put("tool_calls", toolCalls);
        }
        if (toolCallId != null && !toolCallId.isBlank()) {
            m.put("tool_call_id", toolCallId);
        }
        if (name != null && !name.isBlank()) {
            m.put("name", name);
        }
        return m;
    }

    private List<Map<String, Object>> toolSpecs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : toolList()) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", t.getToolName());
            function.put("description", t.getDescription());
            function.put("parameters", t.getInputSchema());
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("type", "function");
            spec.put("function", function);
            out.add(spec);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Response parseResponse(String body) {
        Map<String, Object> root = Json.parseObject(body);
        Object usageObj = root.get("usage");
        int tokens = 0;
        if (usageObj instanceof Map<?, ?> usage) {
            Object total = usage.get("total_tokens");
            if (total instanceof Number n) {
                tokens = n.intValue();
            }
        }
        addTokens(tokens);
        Object choicesObj = root.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            return new Response("API error: empty choices", "", List.of(), tokens);
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return new Response("API error: malformed choice", "", List.of(), tokens);
        }
        Object msgObj = choice.get("message");
        if (!(msgObj instanceof Map<?, ?> message)) {
            return new Response("API error: malformed message", "", List.of(), tokens);
        }
        String content = message.get("content") == null ? "" : String.valueOf(message.get("content"));
        String reasoning = message.get("reasoning_content") == null ? ""
                : String.valueOf(message.get("reasoning_content"));
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        Object callsObj = message.get("tool_calls");
        if (callsObj instanceof List<?> calls) {
            for (Object c : calls) {
                if (c instanceof Map<?, ?> call) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : call.entrySet()) {
                        copy.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    toolCalls.add(copy);
                }
            }
        }
        logger.info("LLM ok: model={}, tokens={}, toolCalls={}", model, tokens, toolCalls.size());
        return new Response(content, reasoning, toolCalls, tokens);
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status == 408 || status >= 500;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String configString(ConfigStore config, String key) {
        if (config == null) {
            return null;
        }
        Object v = config.get(key, null);
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        String out = s;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
