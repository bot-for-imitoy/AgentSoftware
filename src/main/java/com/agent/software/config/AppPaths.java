package com.agent.software.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Resolved filesystem roots for one application instance.
 *
 * <p>Replaces the historical {@code store.PathManager}. Platform defaults follow
 * the XDG / Windows / macOS conventions, but any value explicitly set in
 * {@link AppConfig.Storage} (which already applied
 * {@code env > config.json > default}) wins.
 */
public record AppPaths(Path configDir, Path dataDir, Path cacheDir, Path logDir, String appName) {

    public static final String DEFAULT_APP_NAME = "AgentSoftware";

    public AppPaths {
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(logDir, "logDir");
        appName = appName == null || appName.isBlank() ? DEFAULT_APP_NAME : appName;
    }

    /** Resolve using the process environment and the current platform. */
    public static AppPaths resolve(AppConfig.Storage storage) {
        return resolve(storage, DEFAULT_APP_NAME, System.getenv(), System.getProperty("os.name", "linux"));
    }

    /** Resolve using an explicit environment/platform (used by tests). */
    public static AppPaths resolve(AppConfig.Storage storage, String appName,
                                   Map<String, String> env, String platform) {
        AppConfig.Storage s = storage != null ? storage : new AppConfig.Storage(null, null, null, null);
        Map<String, String> e = env != null ? env : Map.of();
        String os = platform == null ? "" : platform.toLowerCase(Locale.ROOT);
        boolean windows = os.contains("win");
        boolean macos = os.contains("mac") || os.contains("darwin");
        Path home = Paths.get(firstNonBlank(
                windows ? e.get("USERPROFILE") : e.get("HOME"),
                System.getProperty("user.home"), "."));

        Path config = configured(s.configDir())
                ? Paths.get(s.configDir())
                : windows ? fromEnv(e, "APPDATA", home.resolve("AppData/Roaming")).resolve(appName)
                : macos ? home.resolve("Library/Application Support").resolve(appName)
                : fromEnv(e, "XDG_CONFIG_HOME", home.resolve(".config")).resolve(appName);

        Path data = configured(s.dataDir())
                ? Paths.get(s.dataDir())
                : windows ? fromEnv(e, "LOCALAPPDATA", home.resolve("AppData/Local")).resolve(appName)
                : macos ? home.resolve("Library/Application Support").resolve(appName)
                : fromEnv(e, "XDG_DATA_HOME", home.resolve(".local/share")).resolve(appName);

        Path cache = configured(s.cacheDir())
                ? Paths.get(s.cacheDir())
                : windows ? data.resolve("Cache")
                : macos ? home.resolve("Library/Caches").resolve(appName)
                : fromEnv(e, "XDG_CACHE_HOME", home.resolve(".cache")).resolve(appName);

        Path log = configured(s.logDir())
                ? Paths.get(s.logDir())
                : windows ? data.resolve("Logs")
                : macos ? home.resolve("Library/Logs").resolve(appName)
                : fromEnv(e, "XDG_STATE_HOME", home.resolve(".local/state")).resolve(appName);

        return new AppPaths(config, data, cache, log, appName);
    }

    public Path configFile(String... parts) {
        return under(configDir, parts);
    }

    public Path dataFile(String... parts) {
        return under(dataDir, parts);
    }

    public Path cacheFile(String... parts) {
        return under(cacheDir, parts);
    }

    public Path logFile(String... parts) {
        return under(logDir, parts);
    }

    private static Path under(Path base, String... parts) {
        Path p = base;
        if (parts != null) {
            for (String part : parts) {
                if (part != null && !part.isBlank()) {
                    p = p.resolve(part);
                }
            }
        }
        return p;
    }

    private static boolean configured(String value) {
        return value != null && !value.isBlank();
    }

    private static Path fromEnv(Map<String, String> env, String key, Path fallback) {
        String v = env.get(key);
        return configured(v) ? Paths.get(v) : fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return ".";
    }
}
