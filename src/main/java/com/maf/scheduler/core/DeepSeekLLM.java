package com.maf.scheduler.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DeepSeek API 客户端, 支持思维链 (thinking) 模式 (Python 版 DeepSeekLLM).
 *
 * 配置优先级: 显式参数 &gt; 配置文件 (llm.deepseek.* / llm.*) &gt; 环境变量 &gt; 默认值.
 */
public class DeepSeekLLM extends OpenAICompatLLM {

    // ── Configuration (与 Python 版 llm.py 的环境变量一致) ──
    public static final String DEEPSEEK_BASE_URL = System.getenv().getOrDefault(
            "DEEPSEEK_BASE_URL", "https://api.deepseek.com");
    public static final String DEEPSEEK_API_KEY = System.getenv().getOrDefault(
            "DEEPSEEK_API_KEY", "");
    public static final String DEEPSEEK_MODEL = System.getenv().getOrDefault(
            "DEEPSEEK_MODEL", "deepseek-v4-flash");
    public static final boolean DEEPSEEK_THINKING = parseBool(
            System.getenv().getOrDefault("DEEPSEEK_THINKING", "true"));

    /** 是否启用思考模式 (None = 读环境变量 DEEPSEEK_THINKING). */
    public boolean thinking;

    public DeepSeekLLM(String apiKey, String baseUrl, String model,
                       Boolean thinking, String label, ConfigStore configStore) {
        super(apiKey, baseUrl, model, label, configStore);
        this.apiName = "DeepSeek";
        this.apiKeyEnv = "DEEPSEEK_API_KEY";
        this.baseUrlEnv = "DEEPSEEK_BASE_URL";
        this.modelEnv = "DEEPSEEK_MODEL";
        this.defaultBaseUrl = DEEPSEEK_BASE_URL;
        this.defaultModel = DEEPSEEK_MODEL;
        this.requiresApiKey = true;
        resolveConfig(apiKey, baseUrl, model, configStore);
        this.thinking = thinking != null ? thinking
                : boolVal(configStore, "deepseek", "thinking", DEEPSEEK_THINKING);
    }

    /** 便捷构造: 全部走环境变量/配置文件默认. */
    public DeepSeekLLM() {
        this(null, null, null, null, null, null);
    }

    private void resolveConfig(String apiKey, String baseUrl, String model,
                               ConfigStore cs) {
        String provider = "deepseek";
        String envKey = System.getenv().getOrDefault(apiKeyEnv, "");
        this.apiKey = apiKey != null ? apiKey : str(configValue(provider, "api_key", envKey));
        String envBase = System.getenv().getOrDefault(baseUrlEnv, defaultBaseUrl);
        String b = baseUrl != null ? baseUrl : str(configValue(provider, "base_url", envBase));
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        String envModel = System.getenv().getOrDefault(modelEnv, defaultModel);
        this.model = model != null ? model : str(configValue(provider, "model", envModel));
        if (this.apiKey == null || this.apiKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "DeepSeek API key is required. Set DEEPSEEK_API_KEY env var or pass api_key= to DeepSeekLLM().");
        }
    }

    private Object configValue(String provider, String field, Object def) {
        Object v = configStore.get("llm." + provider + "." + field, null);
        return v != null ? v : configStore.get("llm." + field, def);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean boolVal(ConfigStore cs, String provider, String field, boolean def) {
        Object v = cs.get("llm." + provider + "." + field, null);
        if (v == null) {
            v = cs.get("llm." + field, null);
        }
        if (v == null) {
            return def;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return parseBool(String.valueOf(v));
    }

    private static boolean parseBool(String s) {
        if (s == null) {
            return false;
        }
        String v = s.trim().toLowerCase();
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    @Override
    protected void extraPayload(Map<String, Object> payload, Integer maxTokens) {
        // DeepSeek: thinking 开启时注入 thinking 参数, 并保证 max_tokens >= 1024
        if (!thinking) {
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
}
