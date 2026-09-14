package com.agent.software.adapters.llm;

import com.agent.software.config.AppConfig;
import com.agent.software.kernel.AgentException;
import com.agent.software.adapters.llm.provider.Provider;
import com.agent.software.adapters.llm.provider.ProviderManager;

/**
 * Builds an {@link OpenAiCompatibleClient.Endpoint} from {@link AppConfig.Llm}
 * and the bundled provider catalog.
 *
 * <p>This is the only place that knows provider ids map to base URLs, paths and
 * auth headers. API keys come from {@code llm.apiKeys} (already resolved as
 * {@code env > config.json > default} by the config loader), and the model falls
 * back to the provider's documented default.
 */
public final class ProviderEndpointResolver {

    private ProviderEndpointResolver() {
    }

    public static OpenAiCompatibleClient.Endpoint resolve(AppConfig.Llm llm) {
        return resolve(llm, ProviderManager.loadDefaults());
    }

    public static OpenAiCompatibleClient.Endpoint resolve(AppConfig.Llm llm, ProviderManager catalog) {
        if (llm == null || llm.provider() == null || llm.provider().isBlank()) {
            throw new AgentException.ConfigException("llm.provider must be set");
        }
        Provider provider = catalog.find(llm.provider())
                .orElseThrow(() -> new AgentException.ConfigException(
                        "unknown provider '" + llm.provider() + "'; known: " + providerIds(catalog)));

        String key = llm.apiKeys().getOrDefault(provider.id(), "");
        String model = llm.model() == null || llm.model().isBlank()
                ? (provider.defaultModel() == null ? "" : provider.defaultModel())
                : llm.model();

        return new OpenAiCompatibleClient.Endpoint(
                provider.baseUrl(),
                provider.chatCompletionsPath(),
                key,
                provider.authHeader(),
                provider.authScheme(),
                provider.headers(),
                model);
    }

    private static String providerIds(ProviderManager catalog) {
        StringBuilder sb = new StringBuilder();
        for (Provider p : catalog.all()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.id());
        }
        return sb.toString();
    }
}
