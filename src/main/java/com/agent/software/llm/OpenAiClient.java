package com.agent.software.llm;

import com.agent.software.infra.config.AppConfig;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.tool.spi.ToolSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 唯一 LLM 实现：OpenAI 兼容 chat/completions，内置重试、限流排队与余额不足处理。
 *
 * <p>请求体只用 OpenAI 线格式构造：{@code model / messages / temperature / max_tokens}
 * 以及可选 {@code tools + tool_choice:"auto"}。失败通过空的 {@link ChatReply}/{@link ToolReply}
 * 表达（{@link ChatReply#failed()} 判空），不再返回 {@code "[API error: ...]"} 文本。
 */
public final class OpenAiClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiClient.class);

    /** 等待暂停解除 / 等待重试退避时的唤醒粒度。 */
    private static final long POLL_MILLIS = 200L;

    /** 日志里响应体的最大截断长度（避免把整段响应打进去）。 */
    private static final int LOG_BODY_MAX = 500;

    /** 默认 endpoint（构造器防御用，正常由 ProviderResolver 提供）。 */
    private static final String FALLBACK_BASE_URL = "https://api.openai.com/v1";
    private static final String FALLBACK_MODEL = "gpt-4o-mini";

    /** 摘要系统提示词（中文，对齐 master {@code Conversation} 的压缩摘要用途）。 */
    private static final String SUMMARY_SYSTEM_PROMPT =
            "你是一名负责撰写工作总结的专业助理。请用简洁的中文总结以下内容，"
                    + "提取关键决策、行动项以及值得关注的低优先级事件。"
                    + "输出格式：先写一段总结，再列出关键决策与行动项。";

    /** 余额/配额耗尽的响应体关键词（小写匹配，参考 master 的关键词集合）。 */
    private static final String[] INSUFFICIENT_BALANCE_MARKERS = {
            "insufficient_quota", "insufficient quota", "insufficient balance",
            "exceeded your current quota", "out of credits", "not enough balance",
            "no enough balance", "payment required", "quota exceeded",
            "余额不足", "账户余额", "余额已用完", "欠费", "额度不足",
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProviderResolver.Endpoint endpoint;
    private final AppConfig.Llm.Retry retry;
    private final RetryArbiter arbiter;
    private final HttpClient http;
    private final URI chatUrl;

    /** 全局暂停门：返回 true 时不发请求，轮询等待恢复。 */
    private volatile Supplier<Boolean> pausedGate;
    /** 余额不足通知（只触发一次回调，参数为错误说明）。 */
    private volatile Consumer<String> onInsufficientBalance;
    /** 最近一次失败原因（日志与摘要失败提示用）。 */
    private volatile String lastError = "";

    /** 以解析好的 endpoint、重试配置与共享仲裁器构造。 */
    public OpenAiClient(ProviderResolver.Endpoint endpoint, AppConfig.Llm.Retry retry, RetryArbiter arbiter) {
        this.endpoint = endpoint != null
                ? endpoint
                : new ProviderResolver.Endpoint(FALLBACK_BASE_URL, "", FALLBACK_MODEL);
        this.retry = retry != null ? retry : AppConfig.defaults().llm().retry();
        this.arbiter = arbiter != null
                ? arbiter
                : RetryArbiter.forEndpoint(this.endpoint.baseUrl());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(requestTimeoutSeconds()))
                .build();
        // baseUrl 末尾的 "/" 去重后再拼路径，避免出现 "//chat/completions"。
        String base = Text.orEmpty(this.endpoint.baseUrl()).trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            base = FALLBACK_BASE_URL;
        }
        this.chatUrl = URI.create(base + "/chat/completions");
    }

    @Override
    public ChatReply chat(ChatRequest request) {
        List<Message> messages = new ArrayList<>();
        if (request.system() != null && !request.system().isBlank()) {
            messages.add(Message.system(request.system()));
        }
        messages.add(Message.user(Text.orEmpty(request.user())));

        ToolReply reply = send(messages, List.of(), request.temperature(), request.maxTokens());
        if (reply == null) {
            return new ChatReply("", null, 0);
        }
        return new ChatReply(reply.content(), reply.reasoning(), reply.totalTokens());
    }

    @Override
    public ToolReply chatWithTools(ToolChatRequest request) {
        ToolReply reply = send(request.messages(), request.tools(),
                request.temperature(), request.maxTokens());
        return reply != null ? reply : new ToolReply("", null, List.of(), 0);
    }

    @Override
    public ChatReply summarize(String text, double temperature, int maxTokens) {
        ChatReply reply = chat(new ChatRequest(SUMMARY_SYSTEM_PROMPT,
                "请总结以下工作日志：\n" + Text.orEmpty(text), temperature, maxTokens));
        if (reply.failed()) {
            logger.warn("摘要请求失败，返回空结果: {}", lastError);
            return new ChatReply("", null, 0);
        }
        return reply;
    }

    /** 注入全局暂停门：返回 true 时等待而不发起请求。 */
    public void setPausedGate(Supplier<Boolean> gate) {
        this.pausedGate = gate;
    }

    /** 注入余额不足通知（例如暂停公司并提示充值）。 */
    public void setOnInsufficientBalance(Consumer<String> listener) {
        this.onInsufficientBalance = listener;
    }

    // ── 发送与解析 ─────────────────────────────────────────────

    /** 走同一条发送/重试路径；失败返回 null（失败详情在 {@link #lastError}）。 */
    private ToolReply send(List<Message> messages, List<ToolSpec> tools,
                           double temperature, Integer maxTokens) {
        ObjectNode payload = buildPayload(messages, tools, temperature, maxTokens);
        JsonNode data = postWithRetry(payload);
        return data == null ? null : parseReply(data);
    }

    /** 构造 OpenAI 兼容请求体。 */
    private ObjectNode buildPayload(List<Message> messages, List<ToolSpec> tools,
                                    double temperature, Integer maxTokens) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", Text.orEmpty(endpoint.model()));
        ArrayNode messageNodes = root.putArray("messages");
        if (messages != null) {
            for (Message message : messages) {
                messageNodes.add(messageNode(message));
            }
        }
        root.put("temperature", temperature);
        if (maxTokens != null) {
            root.put("max_tokens", maxTokens);
        }
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolNodes = root.putArray("tools");
            for (ToolSpec spec : tools) {
                toolNodes.add(toolNode(spec));
            }
            root.put("tool_choice", "auto");
        }
        return root;
    }

    /** 单条消息 → OpenAI 线格式。 */
    private ObjectNode messageNode(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", roleName(message.role()));
        node.put("content", Text.orEmpty(message.content()));
        if (message.role() == Message.Role.ASSISTANT
                && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCallRequest call : message.toolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", Text.orEmpty(call.callId()));
                callNode.put("type", "function");
                ObjectNode function = callNode.putObject("function");
                function.put("name", Text.orEmpty(call.toolName()));
                function.put("arguments", argumentsJson(call.arguments()));
            }
        }
        if (message.role() == Message.Role.TOOL) {
            node.put("tool_call_id", Text.orEmpty(message.toolCallId()));
        }
        return node;
    }

    /** 工具声明 → OpenAI function 工具。 */
    private ObjectNode toolNode(ToolSpec spec) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "function");
        ObjectNode function = node.putObject("function");
        function.put("name", Text.orEmpty(spec.name()));
        function.put("description", Text.orEmpty(spec.description()));
        Map<String, Object> schema = spec.schema() == null ? Map.of() : spec.schema().toMap();
        function.set("parameters", mapper.valueToTree(schema));
        return node;
    }

    /** 工具参数序列化成 JSON 字符串（OpenAI 的 arguments 是字符串而非对象）。 */
    private String argumentsJson(Payload arguments) {
        Map<String, Object> map = arguments == null ? Map.of() : arguments.asMap();
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            logger.warn("工具参数无法序列化为 JSON，按空对象发送: {}", e.getMessage());
            return "{}";
        }
    }

    /** 解析 2xx 响应体。 */
    private ToolReply parseReply(JsonNode data) {
        JsonNode message = choiceMessage(data);
        String content = textOf(message, "content");
        String reasoning = firstNonBlank(textOf(message, "reasoning_content"), textOf(message, "reasoning"));
        List<ToolCallRequest> toolCalls = parseToolCalls(message);
        // 部分后端（如 reasoning 模型）只回 reasoning_content；无 content 且无工具调用时用它兜底，
        // 否则 ChatReply.failed() 会把一次正常回复误判为失败（对齐 master 行为）。
        if (content.isEmpty() && !reasoning.isEmpty() && toolCalls.isEmpty()) {
            logger.warn("模型未返回 content，回退使用 reasoning_content（{} 字符）", reasoning.length());
            content = reasoning;
        }
        if (content.isEmpty() && toolCalls.isEmpty()) {
            logger.warn("模型返回了空内容（既无 content 也无 tool_calls）");
        }
        return new ToolReply(content, reasoning, toolCalls, parseTokens(data));
    }

    /** 取 choices[0].message；形状不对时返回空对象，后续字段访问自然为空。 */
    private JsonNode choiceMessage(JsonNode data) {
        JsonNode message = data.path("choices").path(0).path("message");
        return message.isObject() ? message : mapper.createObjectNode();
    }

    /** 解析 tool_calls；arguments 是 JSON 字符串，解析失败按空参数处理。 */
    private List<ToolCallRequest> parseToolCalls(JsonNode message) {
        JsonNode raw = message.path("tool_calls");
        if (!raw.isArray() || raw.isEmpty()) {
            return List.of();
        }
        List<ToolCallRequest> out = new ArrayList<>();
        int index = 0;
        for (JsonNode call : raw) {
            JsonNode function = call.path("function");
            String callId = textOf(call, "id");
            if (callId.isBlank()) {
                // provider 没给 id 时补一个，保证 tool 结果消息能通过 tool_call_id 回填。
                callId = "call_" + index;
            }
            out.add(new ToolCallRequest(callId, textOf(function, "name"),
                    parseArguments(textOf(function, "arguments"))));
            index++;
        }
        return out;
    }

    /** arguments JSON 字符串 → Payload；非法 JSON 给空 Payload。 */
    private Payload parseArguments(String json) {
        if (json == null || json.isBlank()) {
            return Payload.empty();
        }
        try {
            Map<String, Object> map = mapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            return Payload.ofMap(map);
        } catch (Exception e) {
            logger.warn("工具调用参数不是合法 JSON，按空参数处理: {}", Text.truncate(json, 120));
            return Payload.empty();
        }
    }

    /** usage.total_tokens，缺失时用 prompt_tokens + completion_tokens。 */
    private static int parseTokens(JsonNode data) {
        JsonNode usage = data.path("usage");
        if (!usage.isObject()) {
            return 0;
        }
        JsonNode total = usage.get("total_tokens");
        if (total != null && total.isNumber()) {
            return total.asInt();
        }
        return intOf(usage.get("prompt_tokens")) + intOf(usage.get("completion_tokens"));
    }

    // ── HTTP + 重试 ────────────────────────────────────────────

    /**
     * 发送 POST 并按需重试；返回解析后的响应 JSON，放弃时返回 null 并记录 {@link #lastError}。
     *
     * <p>可重试：HTTP 429/5xx、超时、IOException。不可重试：HTTP 400/401/403/404 等 4xx
     * （直接返回失败），以及余额/配额耗尽（触发回调后立即失败，避免继续打空账户）。
     */
    private JsonNode postWithRetry(ObjectNode payload) {
        String body;
        try {
            body = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            lastError = "请求体序列化失败: " + e.getMessage();
            logger.error("构建 LLM 请求失败: {}", lastError);
            return null;
        }

        int maxAttempts = Math.max(1, retry.maxAttempts());
        String lastErr = "";
        int attempt = 0;
        while (attempt < maxAttempts) {
            // 暂停门：暂停期间不发请求，轮询等待恢复（可中断）。
            if (!awaitResume()) {
                lastError = "aborted: interrupted";
                logger.warn("LLM 请求在暂停等待中被中断，放弃（已尝试 {} 次）", attempt);
                return null;
            }
            attempt++;
            int retries = attempt - 1;
            // 拥塞期间按重试次数排队；排队途中被暂停/中断则放弃本次排队。
            RetryArbiter.Slot slot = arbiter.acquire(retries, POLL_MILLIS,
                    () -> !isPaused() && !Thread.currentThread().isInterrupted());
            if (slot == null) {
                if (Thread.currentThread().isInterrupted()) {
                    lastError = "aborted: interrupted";
                    logger.warn("LLM 请求排队时被中断，放弃（重试 {} 次）", retries);
                    return null;
                }
                // 排队期间被暂停：不计入尝试次数，回到循环顶部等恢复后重试同一轮。
                attempt--;
                continue;
            }
            boolean succeeded = false;
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(chatUrl)
                        .timeout(Duration.ofSeconds(requestTimeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                String apiKey = Text.orEmpty(endpoint.apiKey());
                if (!apiKey.isBlank()) {
                    // key 为空时不加 Authorization，便于连本地 mock / 无鉴权的本地服务。
                    builder.header("Authorization", "Bearer " + apiKey);
                }
                HttpResponse<String> response =
                        http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                String respBody = response.body();

                if (isInsufficientBalance(status, respBody)) {
                    lastErr = "HTTP " + status + ": " + Text.truncate(respBody, 200);
                    lastError = lastErr;
                    logger.error("LLM 余额/配额不足，触发回调并放弃本次请求: {}", lastErr);
                    notifyInsufficientBalance(status);
                    return null;
                }
                if (status == 429 || status >= 500) {
                    lastErr = "HTTP " + status;
                    arbiter.throttled(lastErr);
                    logger.warn("LLM 请求失败（{}，第 {}/{} 次尝试），{} 秒后重试",
                            lastErr, attempt, maxAttempts, (long) retry.delaySeconds());
                } else if (status >= 400) {
                    lastErr = "HTTP " + status + ": " + Text.truncate(respBody, LOG_BODY_MAX);
                    lastError = lastErr;
                    logger.error("LLM 请求失败且不可重试: {}", lastErr);
                    return null;
                } else {
                    JsonNode data = parseBody(respBody);
                    if (data == null) {
                        lastErr = "空响应体或非法 JSON: " + Text.truncate(respBody, LOG_BODY_MAX);
                        logger.warn("LLM 响应无法解析（第 {}/{} 次尝试），{} 秒后重试",
                                attempt, maxAttempts, (long) retry.delaySeconds());
                    } else {
                        succeeded = true;   // 解析成功的 2xx 才是解除拥塞的信号
                        return data;
                    }
                }
            } catch (HttpTimeoutException e) {
                lastErr = "timeout";
                logger.warn("LLM 请求超时（第 {}/{} 次尝试），{} 秒后重试",
                        attempt, maxAttempts, (long) retry.delaySeconds());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "aborted: interrupted";
                logger.warn("LLM 请求被中断，放弃（第 {}/{} 次尝试）", attempt, maxAttempts);
                return null;
            } catch (IOException e) {
                lastErr = e.getClass().getSimpleName() + ": " + Text.truncate(e.getMessage(), 200);
                logger.warn("LLM 请求 IO 异常（{}，第 {}/{} 次尝试），{} 秒后重试",
                        lastErr, attempt, maxAttempts, (long) retry.delaySeconds());
            } catch (RuntimeException e) {
                lastErr = e.getClass().getSimpleName() + ": " + Text.truncate(e.getMessage(), 200);
                logger.warn("LLM 请求异常（{}，第 {}/{} 次尝试），{} 秒后重试",
                        lastErr, attempt, maxAttempts, (long) retry.delaySeconds());
            } finally {
                // 先归还名额再退避：本请求的退避等待不应挡住排队中的其他人。
                slot.release(succeeded);
            }
            sleepBetweenAttempts();
        }
        lastError = "重试 " + maxAttempts + " 次仍失败: " + lastErr;
        logger.error("LLM 请求连续失败 {} 次，放弃: {}", maxAttempts, lastErr);
        return null;
    }

    /** 解析 2xx 响应体；空体 / 非法 JSON 返回 null（由调用方按可重试处理）。 */
    private JsonNode parseBody(String respBody) {
        if (respBody == null || respBody.isBlank()) {
            return null;
        }
        try {
            JsonNode data = mapper.readTree(respBody);
            return data == null || data.isMissingNode() ? null : data;
        } catch (IOException e) {
            logger.warn("LLM 响应 JSON 解析失败: {}", Text.truncate(e.getMessage(), 200));
            return null;
        }
    }

    /** 暂停期间轮询等待；返回 false 表示线程被中断、必须放弃。 */
    private boolean awaitResume() {
        while (isPaused()) {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 尝试之间退避；可被暂停或中断提前唤醒（暂停时回到循环顶部等待）。 */
    private void sleepBetweenAttempts() {
        long delayMillis = (long) Math.max(0.0, retry.delaySeconds() * 1000.0);
        long deadline = System.currentTimeMillis() + delayMillis;
        while (System.currentTimeMillis() < deadline) {
            if (isPaused() || Thread.currentThread().isInterrupted()) {
                return;
            }
            long remain = Math.max(1L, deadline - System.currentTimeMillis());
            try {
                Thread.sleep(Math.min(POLL_MILLIS, remain));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 暂停门判断（未注入或抛异常都按未暂停处理，避免卡死）。 */
    private boolean isPaused() {
        Supplier<Boolean> gate = pausedGate;
        if (gate == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(gate.get());
        } catch (Exception e) {
            logger.debug("暂停门检查失败，按未暂停处理", e);
            return false;
        }
    }

    /** 是否为"余额/配额耗尽"：HTTP 402，或响应体命中关键词。 */
    private static boolean isInsufficientBalance(int status, String body) {
        if (status == 402) {
            return true;   // Payment Required：账户耗尽的规范状态码
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

    /** 通知余额不足（回调自身异常不得影响请求失败路径）。 */
    private void notifyInsufficientBalance(int status) {
        Consumer<String> listener = onInsufficientBalance;
        if (listener == null) {
            return;
        }
        String reason = "API 报告余额/配额不足（HTTP " + status + "）";
        try {
            listener.accept(reason);
        } catch (Exception e) {
            logger.warn("余额不足回调执行失败", e);
        }
    }

    /** 请求超时秒数（至少 1 秒）。 */
    private int requestTimeoutSeconds() {
        return Math.max(1, retry.timeoutSeconds());
    }

    // ── 小工具 ─────────────────────────────────────────────────

    private static String roleName(Message.Role role) {
        if (role == null) {
            return "user";
        }
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static int intOf(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : 0;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
