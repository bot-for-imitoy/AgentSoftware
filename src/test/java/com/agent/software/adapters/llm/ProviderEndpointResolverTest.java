package com.agent.software.adapters.llm;

import com.agent.software.config.AppConfig;
import com.agent.software.kernel.AgentException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderEndpointResolverTest {

    private static AppConfig.Llm llm(String provider, String model, Map<String, String> keys) {
        return new AppConfig.Llm(provider, model, keys, new AppConfig.Llm.Retry(3, 0.01, 5));
    }

    @Test
    void resolvesOpenAiDialectWithConfiguredKeyAndDefaultModel() {
        OpenAiCompatibleClient.Endpoint endpoint =
                ProviderEndpointResolver.resolve(llm("deepseek", null, Map.of("deepseek", "sk-x")));

        assertEquals("https://api.deepseek.com/v1/chat/completions", endpoint.chatUrl());
        assertEquals("sk-x", endpoint.apiKey());
        assertEquals("Authorization", endpoint.authHeader());
        assertEquals("Bearer", endpoint.authScheme());
        assertEquals("deepseek-chat", endpoint.model());
    }

    @Test
    void explicitModelOverridesProviderDefault() {
        OpenAiCompatibleClient.Endpoint endpoint =
                ProviderEndpointResolver.resolve(llm("openai", "gpt-4o", Map.of("openai", "sk-y")));
        assertEquals("gpt-4o", endpoint.model());
        assertEquals("https://api.openai.com/v1/chat/completions", endpoint.chatUrl());
    }

    @Test
    void resolvesAnthropicDialectHeaders() {
        OpenAiCompatibleClient.Endpoint endpoint =
                ProviderEndpointResolver.resolve(llm("anthropic", null, Map.of("anthropic", "sk-ant")));

        assertEquals("https://api.anthropic.com/v1/messages", endpoint.chatUrl());
        assertEquals("x-api-key", endpoint.authHeader());
        assertNull(endpoint.authScheme());
        assertTrue(endpoint.extraHeaders().containsKey("anthropic-version"));
    }

    @Test
    void unknownProviderFailsFast() {
        assertThrows(AgentException.ConfigException.class,
                () -> ProviderEndpointResolver.resolve(llm("nope", null, Map.of())));
    }

    @Test
    void missingKeyStillBuildsEndpoint() {
        OpenAiCompatibleClient.Endpoint endpoint = ProviderEndpointResolver.resolve(llm("ollama", null, Map.of()));
        assertEquals("", endpoint.apiKey());
        assertEquals("http://localhost:11434/v1/chat/completions", endpoint.chatUrl());
    }
}
