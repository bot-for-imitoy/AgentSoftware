package com.agent.software.config;

import com.agent.software.kernel.AgentException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static ConfigSource env(Map<String, String> values) {
        return key -> Optional.ofNullable(values.get(key));
    }

    @Test
    void defaultsWhenNothingConfigured(@TempDir Path dir) {
        AppConfig c = ConfigLoader.load(dir.resolve("missing.json"), ConfigSource.empty()).toAppConfig();
        assertEquals("openai", c.llm().provider());
        assertEquals(200, c.llm().retry().maxAttempts());
        assertEquals(1.0, c.schedule().secondsPerTick());
        assertEquals(8787, c.web().port());
        assertTrue(c.toolkits().defaults().contains("talk"));
    }

    @Test
    void fileOverridesDefaultsAndEnvOverridesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, """
                { "llm": { "provider": "deepseek", "retry": { "maxAttempts": 5 } },
                  "web": { "port": 9000 } }
                """);

        AppConfig fromFile = ConfigLoader.load(file, ConfigSource.empty()).toAppConfig();
        assertEquals("deepseek", fromFile.llm().provider());
        assertEquals(5, fromFile.llm().retry().maxAttempts());
        assertEquals(9000, fromFile.web().port());

        AppConfig fromEnv = ConfigLoader.load(file, env(Map.of(
                "AGENTSOFTWARE_LLM_PROVIDER", "ollama",
                "AGENTSOFTWARE_WEB_PORT", "1234"))).toAppConfig();
        assertEquals("ollama", fromEnv.llm().provider());
        assertEquals(1234, fromEnv.web().port());
        assertEquals(5, fromEnv.llm().retry().maxAttempts(), "file value must survive env override of another key");
    }

    @Test
    void envKeyMappingHandlesCamelCase() {
        assertEquals("AGENTSOFTWARE_LLM_PROVIDER", ConfigLoader.envKey("llm.provider"));
        assertEquals("AGENTSOFTWARE_LLM_API_KEYS", ConfigLoader.envKey("llm.apiKeys"));
        assertEquals("AGENTSOFTWARE_LLM_RETRY_MAX_ATTEMPTS", ConfigLoader.envKey("llm.retry.maxAttempts"));
        assertEquals("AGENTSOFTWARE_SCHEDULE_SECONDS_PER_TICK", ConfigLoader.envKey("schedule.secondsPerTick"));
    }

    @Test
    void listsAndMapsFromEnvAndFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, """
                { "toolkits": { "default": ["a", "b"] },
                  "llm": { "apiKeys": { "openai": "sk-file" } } }
                """);

        ConfigLoader fromFile = ConfigLoader.load(file, ConfigSource.empty());
        assertEquals(List.of("a", "b"), fromFile.strList("toolkits.default", List.of()));
        assertEquals("sk-file", fromFile.strMap("llm.apiKeys", Map.of()).get("openai"));

        ConfigLoader fromEnv = ConfigLoader.load(file, env(Map.of(
                "AGENTSOFTWARE_TOOLKITS_DEFAULT", "x, y ,z",
                "AGENTSOFTWARE_LLM_API_KEYS", "deepseek=sk-env,openai=sk-env2")));
        assertEquals(List.of("x", "y", "z"), fromEnv.strList("toolkits.default", List.of()));
        assertEquals("sk-env", fromEnv.strMap("llm.apiKeys", Map.of()).get("deepseek"));
        assertEquals("sk-env2", fromEnv.strMap("llm.apiKeys", Map.of()).get("openai"));
    }

    @Test
    void invalidValuesFailFast(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "{ \"web\": { \"port\": \"not-a-number\" } }");
        ConfigLoader loader = ConfigLoader.load(file, ConfigSource.empty());
        assertThrows(AgentException.ConfigException.class, () -> loader.integer("web.port", 1));
    }

    @Test
    void nonObjectRootFailsFast(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config.json");
        Files.writeString(file, "[1,2,3]");
        assertThrows(AgentException.ConfigException.class, () -> ConfigLoader.load(file, ConfigSource.empty()));
    }
}
