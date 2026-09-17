package com.agent.software.infra.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置的三层优先级：env/-D &gt; config.json &gt; 代码默认。
 *
 * <p>测试通过 {@code AGENTSOFTWARE_CONFIG_DIR} 系统属性把配置目录指到临时目录，
 * 因此不会碰真实用户目录。
 */
class ConfigLoaderTest {

    @TempDir
    Path configDir;

    @AfterEach
    void cleanup() {
        System.clearProperty("AGENTSOFTWARE_CONFIG_DIR");
        System.clearProperty("agentsoftware.secondsPerTick");
        System.clearProperty("agentsoftware.web.port");
    }

    private void writeConfig(String json) throws IOException {
        Files.writeString(configDir.resolve("config.json"), json, StandardCharsets.UTF_8);
    }

    @Test
    void 没有配置文件时用代码默认() {
        System.setProperty("AGENTSOFTWARE_CONFIG_DIR", configDir.toString());
        AppConfig config = new ConfigLoader().load();
        assertEquals("openai", config.llm().providerId());
        assertEquals(1.0, config.schedule().secondsPerTick());
        assertEquals(8, config.schedule().shiftStartHour());
        assertEquals(18, config.schedule().shiftEndHour());
        assertEquals(600_000L, config.schedule().wrapUpGraceMillis(), "收尾宽限期默认 600 秒");
        assertTrue(config.toolkits().defaults().contains("memory"));
    }

    @Test
    void 配置文件覆盖默认值() throws IOException {
        System.setProperty("AGENTSOFTWARE_CONFIG_DIR", configDir.toString());
        writeConfig("""
                {
                  "llm": {"provider": "deepseek", "model": "deepseek-chat", "api_key": "sk-x",
                          "base_url": "https://api.deepseek.com/v1",
                          "retry": {"max_attempts": 5, "delay_seconds": 0.5, "timeout_seconds": 30}},
                  "schedule": {"seconds_per_tick": 2.0, "shift_end_hour": 20,
                               "fast_forward_idle_millis": 1000, "wrap_up_grace_millis": 5000},
                  "storage": {"data_dir": "%s"},
                  "web": {"host": "127.0.0.1", "port": 9999, "reply_timeout_millis": 1234},
                  "mail": {"suffix": "example.com",
                           "smtp": {"host": "smtp.example.com", "port": 465, "user": "u",
                                    "password": "p", "from": "a@example.com", "use_ssl": true}},
                  "toolkits": {"defaults": ["note", "todo"]}
                }
                """.formatted(configDir.toString().replace("\\", "\\\\")));

        AppConfig config = new ConfigLoader().load();
        assertEquals("deepseek", config.llm().providerId());
        assertEquals("deepseek-chat", config.llm().model());
        assertEquals("sk-x", config.llm().apiKey());
        assertEquals(5, config.llm().retry().maxAttempts());
        assertEquals(30, config.llm().retry().timeoutSeconds());
        assertEquals(2.0, config.schedule().secondsPerTick());
        assertEquals(20, config.schedule().shiftEndHour());
        assertEquals(1000L, config.schedule().fastForwardIdleMillis());
        assertEquals(5000L, config.schedule().wrapUpGraceMillis());
        assertEquals(configDir.toString(), config.storage().dataDir());
        assertEquals(9999, config.web().port());
        assertEquals(1234L, config.web().replyTimeoutMillis());
        assertEquals("example.com", config.mail().suffix());
        assertTrue(config.mail().smtp().configured());
        assertEquals(465, config.mail().smtp().port());
        assertEquals(java.util.Set.of("note", "todo"), config.toolkits().defaults());
    }

    @Test
    void 系统属性优先于配置文件() throws IOException {
        System.setProperty("AGENTSOFTWARE_CONFIG_DIR", configDir.toString());
        writeConfig("{\"schedule\": {\"seconds_per_tick\": 2.0}, \"web\": {\"port\": 9999}}");
        System.setProperty("agentsoftware.secondsPerTick", "0.5");
        System.setProperty("agentsoftware.web.port", "1234");

        AppConfig config = new ConfigLoader().load();
        assertEquals(0.5, config.schedule().secondsPerTick());
        assertEquals(1234, config.web().port());
    }

    @Test
    void 配置目录解析与父目录创建() throws IOException {
        System.setProperty("AGENTSOFTWARE_CONFIG_DIR", configDir.toString());
        AppPaths paths = AppPaths.resolve(new AppConfig.Storage(configDir.resolve("data").toString()));
        assertEquals(configDir.resolve("data"), paths.dataDir());
        Path file = paths.dataFile("a", "b.json");
        paths.ensure(file);
        assertTrue(Files.isDirectory(file.getParent()), "ensure 应当创建父目录");
    }

    @Test
    void 路径拼接是纯函数() {
        AppPaths paths = AppPaths.resolve(new AppConfig.Storage(configDir.toString()));
        assertEquals(configDir.resolve("x").resolve("y.json"), paths.dataFile("x", "y.json"));
        assertEquals(configDir, paths.dataDir());
    }

    @Test
    void secondsPerTick必须为正() {
        assertThrows(IllegalArgumentException.class,
                () -> com.agent.software.sim.clock.ShiftCalendar.of(0.0, 8, 18));
    }
}
