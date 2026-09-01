package com.agent.software.llm;

import com.agent.software.store.ConfigStore;
import com.agent.software.utils.Json;
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
 * 统一的 OpenAI 兼容 chat/completions 客户端 (Python 版 llm.py 的 OpenAICompatLLM).
 *
 * <p>所有后端 (OpenAI 官方 / DeepSeek / 本地 vLLM、LM Studio 等) 的请求协议完全一致
 * (OpenAI 格式), 因此不再区分 provider: 客户端层次只有 {@link LLM} 接口 + 本实现,
 * 只读取 OpenAI 格式的环境变量.
 *
 * <p><b>配置解析统一优先级</b> (高 → 低, 与 {@link ConfigStore} 一致):
 * <ol>
 *   <li>构造器显式参数 (apiKey / baseUrl / model);</li>
 *   <li>Java 参数: 系统属性 {@code -DOPENAI_API_KEY=...} 等 (键名与环境变量一致);</li>
 *   <li>环境变量: {@code OPENAI_API_KEY} / {@code OPENAI_BASE_URL} / {@code OPENAI_MODEL};</li>
 *   <li>配置文件: {@link ConfigStore} 点号路径 {@code llm.api_key} / {@code llm.base_url} /
 *       {@code llm.model};</li>
 *   <li>默认值.</li>
 * </ol>
 */
public class OpenAICompatLLM implements LLM {

    private static final Logger logger = LoggerFactory.getLogger(OpenAICompatLLM.class);

    /** OpenAI 格式环境变量名 (系统属性使用相同键名). */
    public static final String API_KEY_ENV = "OPENAI_API_KEY";
    public static final String BASE_URL_ENV = "OPENAI_BASE_URL";
    public static final String MODEL_ENV = "OPENAI_MODEL";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    // ── 请求失败重试 (用户指定) ────────────────────────────────
    // 可恢复错误等 retryDelay 秒 (10s) 重试, 最多 retryMax 次 (200);
    // 不可恢复的客户端错误 (400/401/403/404 等) 不重试.
    public double retryDelay = 10.0;
    public int retryMax = 200;
    public int apiTimeoutSeconds = 120;

    /** 日志前缀 (如 "OpenAI"). */
    public String apiName = "OpenAI";

    protected String apiKey;
    protected String baseUrl;
    protected String model;
    protected String label = "";                 // 角色标识 (DEBUG 日志前缀)
    protected String retryError = "";            // 最近一次请求失败原因
    protected final ConfigStore configStore;

    /** 便捷构造: 全部走分层配置 (系统属性 &gt; 环境变量 &gt; ConfigStore &gt; 默认值). */
    public OpenAICompatLLM() {
        this(null, null, null, null, null);
    }

    /**
     * 完整构造.
     *
     * @param apiKey      显式 API Key (null = 分层解析 {@code OPENAI_API_KEY})
     * @param baseUrl     显式 Base URL (null = 分层解析 {@code OPENAI_BASE_URL})
     * @param model       显式模型名 (null = 分层解析 {@code OPENAI_MODEL})
     * @param label       角色标识 (DEBUG 日志前缀)
     * @param configStore 配置存储 (null = 默认路径)
     */
    public OpenAICompatLLM(String apiKey, String baseUrl, String model,
                           String label, ConfigStore configStore) {
        this(apiKey, baseUrl, model, label, configStore, null, null);
    }

    /**
     * 测试注入版: env / props 为配置快照 (null = 读取真实系统属性 / 环境变量),
     * 与 PathManager 的注入方式一致.
     */
    OpenAICompatLLM(String apiKey, String baseUrl, String model,
                    String label, ConfigStore configStore,
                    Map<String, String> env, Map<String, String> props) {
        this.configStore = configStore != null ? configStore : new ConfigStore();
        this.label = label != null ? label : "";
        this.apiKey = first(apiKey, (String) this.configStore.get("llm.api_key", System.getenv("OPENAI_APIKEY")));
        this.baseUrl = stripSlash(first(baseUrl, (String) this.configStore.get("llm.base_url", System.getenv("OPENAI_BASEURL"))));
        this.model = first(model, (String) this.configStore.get("llm.model", System.getenv("OPENAI_MODEL")));
    }

    /** ConfigStore 点号路径: {@code llm.&lt;field&gt;}. */
    private static String[] storeKeys(String field) {
        return new String[]{"llm." + field};
    }

    private static String first(String explicit, String resolved) {
        return explicit != null ? explicit : resolved;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    // ── 调试日志 (带角色前缀) ─────────────────────────────

    protected void debug(String msg, Object... args) {
        if (label != null && !label.isEmpty()) {
            logger.debug("[" + label + "] " + msg, args);
        } else {
            logger.debug(msg, args);
        }
    }

    // ── Public API (same interface as MockLLM) ─────────────

    @Override
    public LLM.ChatResponse chat(String system, String user, double temperature, Integer maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (system != null && !system.isEmpty()) {
            messages.add(msg("system", system));
            debug("chat: 追加 system 消息 ({} 字符)", system.length());
        }
        messages.add(msg("user", user));
        debug("chat: 追加 user 消息 ({} 字符)", user.length());

        int mt = maxTokens != null ? maxTokens : 512;
        Object[] result = callApi(messages, temperature, mt);
        String text = (String) result[0];
        Map<String, Object> usage = (Map<String, Object>) result[1];
        int tokens = usage != null ? intOf(usage.get("total_tokens")) : 0;
        return new LLM.ChatResponse(text, tokens);
    }

    @Override
    public LLM.ChatResponse summarize(String logText, double temperature, Integer maxTokens) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("role", "system");
        system.put("content", "你是一个专业的助理，负责写工作总结。请用简洁的中文总结以下内容，"
                + "提取关键决策、待办事项和值得关注的低优先级事件。"
                + "输出格式：先写一段总结，然后列出关键决策和待办事项。");
        messages.add(system);
        messages.add(msg("user", "请总结今天的工作日志：\n" + logText));
        debug("summarize: 追加 user 消息 ({} 字符)", logText.length());

        int mt = maxTokens != null ? maxTokens : 256;
        Object[] result = callApi(messages, temperature, mt);
        String text = (String) result[0];
        Map<String, Object> usage = (Map<String, Object>) result[1];
        int tokens = usage != null ? intOf(usage.get("total_tokens")) : 0;
        return new LLM.ChatResponse(text, tokens);
    }

    @Override
    public LLM.ToolsResponse chatWithTools(List<Map<String, Object>> messages,
                                           List<Map<String, Object>> tools,
                                           double temperature, Integer maxTokens) {
        URI url = URI.create(baseUrl);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
        if (maxTokens != null) {
            payload.put("max_tokens", maxTokens);
        }
        debug("{} API call (tools): model={} messages={} tools={}",
                apiName, model, messages.size(), tools.size());

        Map<String, Object> data = postWithRetry(url, payload);
        if (data == null) {
            return new LLM.ToolsResponse("[API error: " + retryError + "]", List.of(), null);
        }
        Map<String, Object> message = choiceMessage(data);
        String content = strOf(message.get("content"));
        Object reasoningObj = message.get("reasoning_content");
        if (reasoningObj == null) {
            reasoningObj = message.get("reasoning");
        }
        String reasoning = strOf(reasoningObj);
        List<Map<String, Object>> rawCalls = listOfMaps(message.get("tool_calls"));
        if (!reasoning.isEmpty()) {
            debug("{} reasoning ({} chars)", apiName, reasoning.length());
        }
        if (content.isEmpty() && !reasoning.isEmpty() && rawCalls.isEmpty()) {
            logger.warn("{}: empty content, falling back to reasoning_content", apiName);
            content = reasoning;
        }
        Map<String, Object> usage = mapOf(data.get("usage"));
        if (!usage.isEmpty()) {
            debug("{} tokens: prompt={} completion={} total={}", apiName,
                    usage.getOrDefault("prompt_tokens", "?"),
                    usage.getOrDefault("completion_tokens", "?"),
                    usage.getOrDefault("total_tokens", "?"));
        }
        return new LLM.ToolsResponse(content, rawCalls, usage.isEmpty() ? null : usage);
    }

    // ── Internal ───────────────────────────────────────────

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object o) {
        if (o instanceof Map) {
            return (Map<String, Object>) o;
        }
        return new LinkedHashMap<>();
    }

    private static List<Map<String, Object>> listOfMaps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) {
                    out.add((Map<String, Object>) item);
                }
            }
        }
        return out;
    }

    private static String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? 0 : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Object> choiceMessage(Map<String, Object> data) {
        Object choices = data.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> m && m.get("message") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) m.get("message");
                return message;
            }
        }
        return new LinkedHashMap<>();
    }

    /**
     * 发送 POST 请求, 失败自动重试 (限速/超时/5xx 等可恢复错误).
     *
     * @return 响应 JSON Map; 放弃时返回 null (原因在 retryError).
     */
    protected Map<String, Object> postWithRetry(URI url, Map<String, Object> payload) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(apiTimeoutSeconds))
                .build();
        String lastErr = "";
        for (int attempt = 1; attempt <= retryMax; attempt++) {
            HttpRequest.Builder rb = HttpRequest.newBuilder(url)
                    .timeout(Duration.ofSeconds(apiTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)));
            if (apiKey != null && !apiKey.isEmpty()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }
            HttpRequest request = rb.build();
            try {
                HttpResponse<String> resp = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status == 429 || status >= 500) {
                    lastErr = "HTTP " + status;
                    logger.warn("{} API 请求失败 ({}, 第 {}/{} 次), {}s 后重试",
                            apiName, lastErr, attempt, retryMax, (long) retryDelay);
                    sleep();
                    continue;
                }
                if (status >= 400) {
                    retryError = "HTTP " + status + ": " + truncate(resp.body(), 200);
                    logger.error("{} API 请求失败, 不可恢复: {}", apiName, retryError);
                    return null;
                }
                return parseBody(resp.body());
            } catch (java.net.http.HttpTimeoutException e) {
                lastErr = "timeout";
                logger.warn("{} API 请求超时 (第 {}/{} 次), {}s 后重试", apiName, attempt, retryMax, (long) retryDelay);
                sleep();
            } catch (Exception e) {
                lastErr = e.getClass().getSimpleName() + ": " + truncate(e.getMessage(), 120);
                logger.warn("{} API 请求错误 ({}, 第 {}/{} 次), {}s 后重试",
                        apiName, lastErr, attempt, retryMax, (long) retryDelay);
                sleep();
            }
        }
        retryError = "重试 " + retryMax + " 次仍失败: " + lastErr;
        logger.error("{} API 请求失败 {} 次, 放弃: {}", apiName, retryMax, lastErr);
        return null;
    }

    private void sleep() {
        try {
            Thread.sleep((long) (retryDelay * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static Map<String, Object> parseBody(String body) throws java.io.IOException {
        return Json.parseObject(body);
    }

    /** 核心 API 调用. 返回 (contentText, usageMap). */
    protected Object[] callApi(List<Map<String, Object>> messages, double temperature, int maxTokens) {
        URI url = URI.create(baseUrl + "/v1/chat/completions");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("max_tokens", maxTokens);
        debug("{} API call: model={} messages={}", apiName, model, messages.size());

        Map<String, Object> data = postWithRetry(url, payload);
        if (data == null) {
            return new Object[]{"[API error: " + retryError + "]", null};
        }
        Map<String, Object> message = choiceMessage(data);
        String content = strOf(message.get("content"));
        Object reasoningObj = message.get("reasoning_content");
        if (reasoningObj == null) {
            reasoningObj = message.get("reasoning");
        }
        String reasoning = strOf(reasoningObj);
        if (!reasoning.isEmpty()) {
            debug("{} reasoning ({} chars)", apiName, reasoning.length());
        }
        if (content.isEmpty() && !reasoning.isEmpty()) {
            logger.warn("{}: empty content, falling back to reasoning_content", apiName);
            content = reasoning;
        }
        Map<String, Object> usage = mapOf(data.get("usage"));
        if (!usage.isEmpty()) {
            debug("{} tokens: prompt={} completion={} total={}", apiName,
                    usage.getOrDefault("prompt_tokens", "?"),
                    usage.getOrDefault("completion_tokens", "?"),
                    usage.getOrDefault("total_tokens", "?"));
        }
        if (content.isEmpty()) {
            logger.warn("{} returned empty content.", apiName);
        }
        return new Object[]{content, usage.isEmpty() ? null : usage};
    }
}
