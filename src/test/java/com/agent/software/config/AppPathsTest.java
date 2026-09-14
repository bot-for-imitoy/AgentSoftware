package com.agent.software.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {

    @Test
    void explicitStorageWinsOverPlatformDefaults() {
        AppConfig.Storage storage = new AppConfig.Storage("/data-root", "/config-root", "/cache-root", "/log-root");
        AppPaths p = AppPaths.resolve(storage, "App", Map.of("HOME", "/home/u"), "Linux");
        assertEquals(Path.of("/data-root"), p.dataDir());
        assertEquals(Path.of("/config-root"), p.configDir());
        assertEquals(Path.of("/cache-root"), p.cacheDir());
        assertEquals(Path.of("/log-root"), p.logDir());
    }

    @Test
    void linuxUsesXdgEnvironment() {
        Map<String, String> env = Map.of(
                "HOME", "/home/u",
                "XDG_CONFIG_HOME", "/home/u/.config-custom",
                "XDG_DATA_HOME", "/home/u/.data-custom",
                "XDG_CACHE_HOME", "/home/u/.cache-custom",
                "XDG_STATE_HOME", "/home/u/.state-custom");
        AppPaths p = AppPaths.resolve(new AppConfig.Storage(null, null, null, null), "MyApp", env, "Linux");
        assertEquals(Path.of("/home/u/.config-custom/MyApp"), p.configDir());
        assertEquals(Path.of("/home/u/.data-custom/MyApp"), p.dataDir());
        assertEquals(Path.of("/home/u/.cache-custom/MyApp"), p.cacheDir());
        assertEquals(Path.of("/home/u/.state-custom/MyApp"), p.logDir());
    }

    @Test
    void macosUsesLibraryDirectories() {
        Map<String, String> env = Map.of("HOME", "/Users/u");
        AppPaths p = AppPaths.resolve(new AppConfig.Storage(null, null, null, null), "MyApp", env, "Mac OS X");
        assertEquals(Path.of("/Users/u/Library/Application Support/MyApp"), p.configDir());
        assertEquals(Path.of("/Users/u/Library/Caches/MyApp"), p.cacheDir());
        assertEquals(Path.of("/Users/u/Library/Logs/MyApp"), p.logDir());
    }

    @Test
    void fileHelpersAppendParts() {
        AppPaths p = AppPaths.resolve(new AppConfig.Storage("/d", "/c", "/k", "/l"), "App", Map.of(), "Linux");
        assertEquals(Path.of("/d/sub/file.json"), p.dataFile("sub", "file.json"));
        assertEquals(Path.of("/c/config.json"), p.configFile("config.json"));
    }

    @Test
    void storageEnvOverridesPlatformDefaultsButNotExplicitStorage() {
        Map<String, String> env = Map.of(
                "HOME", "/home/u",
                "AGENTSOFTWARE_STORAGE_DATA_DIR", "/env/data",
                "AGENTSOFTWARE_STORAGE_CONFIG_DIR", "/env/config");

        AppPaths fromEnv = AppPaths.resolve(new AppConfig.Storage(null, null, null, null), "App", env, "Linux");
        assertEquals(Path.of("/env/data"), fromEnv.dataDir());
        assertEquals(Path.of("/env/config"), fromEnv.configDir());
        assertEquals(Path.of("/env/config"), fromEnv.configFile("config.json").getParent());

        AppPaths explicit = AppPaths.resolve(
                new AppConfig.Storage("/explicit/data", "/explicit/config", null, null), "App", env, "Linux");
        assertEquals(Path.of("/explicit/data"), explicit.dataDir());
        assertEquals(Path.of("/explicit/config"), explicit.configDir());
    }
}
