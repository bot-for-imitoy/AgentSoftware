package com.agent.software.llm;

import com.agent.software.store.ConfigStore;

/**
 * 本地 Ollama 客户端 (OpenAI 兼容端点, 免 API Key) — Python 版 OllamaLLM.
 */
public class OllamaLLM extends OpenAICompatLLM {

    // ── Configuration ──────────────────────────────────────────
    public static final String OLLAMA_BASE_URL = System.getenv().getOrDefault(
            "OLLAMA_BASE_URL", "http://localhost:11434");
    public static final String OLLAMA_MODEL = System.getenv().getOrDefault(
            "OLLAMA_MODEL", "gemma4-16k:latest");

    public OllamaLLM(String apiKey, String baseUrl, String model, String label,
                     ConfigStore configStore) {
        super(apiKey, baseUrl, model, label, configStore);
        this.apiName = "Ollama";
        this.apiKeyEnv = "";
        this.baseUrlEnv = "OLLAMA_BASE_URL";
        this.modelEnv = "OLLAMA_MODEL";
        this.defaultBaseUrl = OLLAMA_BASE_URL;
        this.defaultModel = OLLAMA_MODEL;
        this.requiresApiKey = false;
        resolveConfig(apiKey, baseUrl, model, configStore);
    }

    /** 便捷构造: 默认连接本地 http://localhost:11434 的 gemma4-16k:latest. */
    public OllamaLLM() {
        this(null, null, null, null, null);
    }

    private void resolveConfig(String apiKey, String baseUrl, String model,
                               ConfigStore cs) {
        String provider = "ollama";
        String envKey = System.getenv().getOrDefault(apiKeyEnv, "");
        this.apiKey = apiKey != null ? apiKey : str(configValue(provider, "api_key", envKey));
        String envBase = System.getenv().getOrDefault(baseUrlEnv, defaultBaseUrl);
        String b = baseUrl != null ? baseUrl : str(configValue(provider, "base_url", envBase));
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        String envModel = System.getenv().getOrDefault(modelEnv, defaultModel);
        this.model = model != null ? model : str(configValue(provider, "model", envModel));
    }

    private Object configValue(String provider, String field, Object def) {
        Object v = configStore.get("llm." + provider + "." + field, null);
        return v != null ? v : configStore.get("llm." + field, def);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
