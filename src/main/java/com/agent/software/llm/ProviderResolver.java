package com.agent.software.llm;

import com.agent.software.infra.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 供应商解析：把 providerId + model + apiKey 组合成可直接调用的 Endpoint。
 *
 * <p>解析优先级（高 → 低）：
 * <ul>
 *   <li>baseUrl：{@code llm.baseUrl()} → 供应商定义 {@code base_url} → {@code https://api.openai.com/v1}；</li>
 *   <li>apiKey：{@code llm.apiKey()} → 本地文件的 {@code api_keys} → 供应商 {@code api_key_env}
 *       命名的环境变量 / 同名 {@code -D} 系统属性 → 空串（本地 mock 无需鉴权）；</li>
 *   <li>model：{@code llm.model()} → 供应商 {@code default_model} → {@code gpt-4o-mini}。</li>
 * </ul>
 *
 * <p>providerId 在目录里找不到时退回内置 OpenAI 定义并记 warn，保证配置写错也能起来。
 */
public final class ProviderResolver {

    private static final Logger logger = LoggerFactory.getLogger(ProviderResolver.class);

    private static final String FALLBACK_BASE_URL = "https://api.openai.com/v1";
    private static final String FALLBACK_API_KEY_ENV = "OPENAI_API_KEY";
    private static final String FALLBACK_MODEL = "gpt-4o-mini";

    /** 解析出 base URL、API key 与最终模型名。 */
    public static Endpoint resolve(AppConfig.Llm llm, ProviderCatalog catalog) {
        AppConfig.Llm cfg = llm != null ? llm : AppConfig.defaults().llm();
        String providerId = cfg.providerId() == null ? "" : cfg.providerId().trim();

        ProviderCatalog.ProviderDef def = catalog == null || providerId.isEmpty()
                ? null
                : catalog.find(providerId).orElse(null);
        if (def == null) {
            // 找不到就用内置兜底：配置里的 providerId 拼错不应该让整个程序起不来。
            logger.warn("未找到供应商定义 '{}'，回退到内置 OpenAI 兜底（base_url={}, api_key_env={}, default_model={}）",
                    providerId, FALLBACK_BASE_URL, FALLBACK_API_KEY_ENV, FALLBACK_MODEL);
            def = new ProviderCatalog.ProviderDef(providerId, providerId, FALLBACK_BASE_URL,
                    FALLBACK_API_KEY_ENV, FALLBACK_MODEL, true);
        }

        String baseUrl = firstNonBlank(cfg.baseUrl(), def.baseUrl(), FALLBACK_BASE_URL);
        String apiKey = firstNonBlank(cfg.apiKey(), localApiKey(catalog, providerId),
                fromEnvironment(def.apiKeyEnv()), "");
        String model = firstNonBlank(cfg.model(), def.defaultModel(), FALLBACK_MODEL);
        return new Endpoint(baseUrl, apiKey, model);
    }

    /** 本地覆盖文件里登记的 key（没有目录或没登记时为 null）。 */
    private static String localApiKey(ProviderCatalog catalog, String providerId) {
        return catalog == null ? null : catalog.apiKeyOverride(providerId).orElse(null);
    }

    /** 读取 api_key_env 命名的环境变量，其次同名 {@code -D} 系统属性。 */
    private static String fromEnvironment(String envName) {
        if (envName == null || envName.isBlank()) {
            return null;
        }
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        String fromProp = System.getProperty(envName);
        return fromProp == null || fromProp.isBlank() ? null : fromProp.trim();
    }

    /** 返回第一个非空白值；全为空时返回最后一项（约定为 ""）。 */
    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return values.length == 0 ? "" : (values[values.length - 1] == null ? "" : values[values.length - 1]);
    }

    /** 一次 LLM 调用所需的连接三元组。 */
    public record Endpoint(String baseUrl, String apiKey, String model) {
    }
}
