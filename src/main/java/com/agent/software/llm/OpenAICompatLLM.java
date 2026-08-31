package com.agent.software.llm;

import com.agent.software.store.ConfigStore;
import com.agent.software.utils.Json;
import com.agent.software.utils.LayeredConfig;
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
 * <p>DeepSeek / Ollama 等 OpenAI 兼容后端的请求协议完全一致 (OpenAI 格式), 差异只有
 * 环境变量名 / 默认模型 / 是否需要 API Key / 是否注入 thinking 参数 — 因此合并为单个
 * <b>具体类</b>, 通过 {@code provider} 参数选择后端, 不再需要 DeepSeekLLM / OllamaLLM
 * 子类 (也不再存在抽象基类, 客户端层次只有 {@link LLM} 接口 + 本实现).
 *
 * <p><b>配置解析统一优先级</b> (高 → 低, 与 {@link LayeredConfig} 一致):
 * <ol>
 *   <li>构造器显式参数 (apiKey / baseUrl / model / thinking);</li>
 *   <li>Java 参数: 系统属性 {@code -DDEEPSEEK_API_KEY=...} 等 (键名与环境变量一致);</li>
 *   <li>环境变量: {@code DEEPSEEK_API_KEY} / {@code OLLAMA_BASE_URL} 等;</li>
 *   <li>配置文件: {@link ConfigStore} 点号路径 {@code llm.&lt;provider&gt;.&lt;field&gt;} 与通用
 *       {@code llm.&lt;field&gt;};</li>
 *   <li>默认值.</li>
 * </ol>
 * 后端选择 (provider) 同样分层读取: {@code -DLLM_PROVIDER} / {@code LLM_PROVIDER} /
 * {@code llm.provider}, 缺省 {@code deepseek}.
 */
public class OpenAICompatLLM implements LLM {

    private static final Logger logger = LoggerFactory.getLogger(OpenAICompatLLM.class);

    /** 默认后端. */
    public static final String DEFAULT_PROVIDER = "deepseek";

    // ── 请求失败重试 (用户指定) ────────────────────────────────
    // 可恢复错误等 retryDelay 秒 (10s) 重试, 最多 retryMax 次 (200);
    // 不可恢复的客户端错误 (400/401/403/404 等) 不重试.
    public double retryDelay = 10.0;
    public int retryMax = 200;
    public int apiTimeoutSeconds = 120;

    // ── 后端规格 (由 provider 决定) ─────────────────────────
    /** 后端标识: "deepseek" | "ollama" | 自定义 OpenAI 兼容名. */
    public final String provider;
    /** 日志前缀 (如 "DeepSeek"). */
    public String apiName = "LLM";
    /** API Key 环境变量名 (无需 Key 的后端为空串). */
    public String apiKeyEnv = "";
    public String baseUrlEnv = "";
    public String modelEnv = "";
    public String defaultBaseUrl = "";
    public String defaultModel = "";
    public boolean requiresApiKey = false;
    /** 是否启用思考模式 (仅 deepseek, 缺省 true). */
    public boolean thinking;

    protected String apiKey;
    protected String baseUrl;
    protected String model;
    protected String label = "";                 // 角色标识 (DEBUG 日志前缀)
    protected String retryError = "";            // 最近一次请求失败原因
    protected final ConfigStore configStore;

    /** 便捷构造: provider 必填, 其余全部走分层配置 (Java 参数 &gt; 环境变量 &gt; ConfigStore). */
    public OpenAICompatLLM(String provider) {
        this(provider, null, null, null, null, null, null);
    }

    /**
     * 完整构造.
     *
     * @param provider 后端: "deepseek" / "ollama" / 自定义 OpenAI 兼容名; null = 分层解析 LLM_PROVIDER
     * @param apiKey   显式 API Key (null = 分层解析)
     * @param baseUrl  显式 Base URL (null = 分层解析)
     * @param model    显式模型名 (null = 分层解析)
     * @param thinking 显式 thinking 开关 (仅 deepseek 生效; null = 分层解析)
     * @param label    角色标识 (DEBUG 日志前缀)
     */
    public OpenAICompatLLM(String provider, String apiKey, String baseUrl, String model,
                           Boolean thinking, String label, ConfigStore configStore) {
        this(provider, apiKey, baseUrl, model, thinking, label, configStore, null, null);
    }

    /**
     * 测试注入版: env / props 为配置快照 (null = 读取真实系统属性 / 环境变量),
     * 与 PathManager 的注入方式一致.
     */
    OpenAICompatLLM(String provider, String apiKey, String baseUrl, String model,
                    Boolean thinking, String label, ConfigStore configStore,
                    Map<String, String> env, Map<String, String> props) {
        this.configStore = configStore != null ? configStore : new ConfigStore();
        this.provider = (provider != null && !provider.isEmpty())
                ? provider.toLowerCase() : resolveProvider(env, props);
        applyProvider(this.provider);
        this.label = label != null ? label : "";
        this.apiKey = first(apiKey, LayeredConfig.get(apiKeyEnv, configStore,
                storeKeys("api_key"), "", env, props));
        this.baseUrl = stripSlash(first(baseUrl, LayeredConfig.get(baseUrlEnv, configStore,
                storeKeys("base_url"), defaultBaseUrl, env, props)));
        this.model = first(model, LayeredConfig.get(modelEnv, configStore,
                storeKeys("model"), defaultModel, env, props));
        this.thinking = "deepseek".equals(this.provider)
                ? LayeredConfig.getBool("DEEPSEEK_THINKING", configStore,
                        storeKeys("thinking"), true, env, props)
                : false;
        if (requiresApiKey && (this.apiKey == null || this.apiKey.isEmpty())) {
            throw new IllegalArgumentException(String.format(
                    "%s API key is required. Set %s (system property or env var) or "
                            + "llm.%s.api_key / llm.api_key in the config file, or pass api_key= to OpenAICompatLLM().",
                    apiName, apiKeyEnv, this.provider));
        }
    }

    /** 解析 LLM 后端: Java 参数 &gt; 环境变量 &gt; 配置文件 (llm.provider) &gt; 默认 deepseek. */
    public static String resolveProvider() {
        return resolveProvider(null, null);
    }

    static String resolveProvider(Map<String, String> env, Map<String, String> props) {
        return LayeredConfig.get("LLM_PROVIDER", new ConfigStore(),
                new String[]{"llm.provider"}, DEFAULT_PROVIDER, env, props);
    }

    // ── 后端规格 ────────────────────────────────────────────

    private void applyProvider(String provider) {
        switch (provider) {
            case "ollama" -> {
                apiName = "Ollama";
                apiKeyEnv = "";
                baseUrlEnv = "OLLAMA_BASE_URL";
                modelEnv = "OLLAMA_MODEL";
                defaultBaseUrl = "http://localhost:11434";
                defaultModel = "gemma4-16k:latest";
                requiresApiKey = false;
            }
            case "deepseek" -> {
                apiName = "DeepSeek";
                apiKeyEnv = "DEEPSEEK_API_KEY";
                baseUrlEnv = "DEEPSEEK_BASE_URL";
                modelEnv = "DEEPSEEK_MODEL";
                defaultBaseUrl = "https://api.deepseek.com";
                defaultModel = "deepseek-v4-flash";
                requiresApiKey = true;
            }
            default -> {
                // 自定义 OpenAI 兼容后端 (vLLM / LM Studio / 私有网关等)
                apiName = provider;
                apiKeyEnv = "OPENAI_API_KEY";
                baseUrlEnv = "OPENAI_BASE_URL";
                modelEnv = "OPENAI_MODEL";
                defaultBaseUrl = "https://api.openai.com";
                defaultModel = "gpt-4o-mini";
                requiresApiKey = false;
            }
        }
    }

    /** ConfigStore 点号路径: 优先 provider 专属, 回退通用. */
    private String[] storeKeys(String field) {
        return new String[]{"llm." + provider + "." + field, "llm." + field};
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
        URI url = URI.create(baseUrl + "/v1/chat/completions");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", temperature);
        payload.put("tools", tools);
        payload.put("tool_choice", "auto");
        if (maxTokens != null) {
            payload.put("max_tokens", maxTokens);
        }
        applyExtraPayload(payload, maxTokens);
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

    // ── 后端私有参数注入 ───────────────────────────────────

    /** 发送前向 payload 注入后端私有参数 (deepseek 思考模式; 其余后端不注入). */
    private void applyExtraPayload(Map<String, Object> payload, Integer maxTokens) {
        if (!"deepseek".equals(provider) || !thinking) {
            return;
        }
        Map<String, Object> thinkingPayload = new LinkedHashMap<>();
        thinkingPayload.put("type", "enabled");
        payload.put("thinking", thinkingPayload);
        Object cur = payload.get("max_tokens");
        if (cur instanceof Number n && n.intValue() < 1024) {
            payload.put("max_tokens", 1024);
        }
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
        applyExtraPayload(payload, maxTokens);
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
