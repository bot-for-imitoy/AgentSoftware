package com.agent.software.llm;

import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.config.AppConfig.Llm;

/**
 * 供应商解析：把 providerId + model + apiKey 组合成可直接调用的 Endpoint。
 */
public final class ProviderResolver {

    /** 解析出 base URL、API key 与最终模型名。 */
    public static Endpoint resolve(AppConfig.Llm llm, ProviderCatalog catalog) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 一次 LLM 调用所需的连接三元组。 */
    public record Endpoint(String baseUrl, String apiKey, String model) {
    }
}
