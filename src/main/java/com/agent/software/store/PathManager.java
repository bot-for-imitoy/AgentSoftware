package com.agent.software.store;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

/**
 * PathManager — cross-platform application path management (Python version path_manager.py).
 *
 * Returns the application-specific directory according to each platform's directory
 * conventions, serving as the single entry point for project-wide paths.
 * Explicit environment variable overrides (highest priority): {@code <ENV_PREFIX>_CONFIG_DIR / _DATA_DIR /
 * _CACHE_DIR / _LOG_DIR}; default ENV_PREFIX = app_name uppercased (underscores).
 */
public class PathManager {

    private final String appName;
    private final Map<String, String> env;
    private final String platform;
    private final String envPrefix;

    public PathManager(String appName) {
        this(appName, null, System.getenv(), System.getProperty("os.name", "linux"));
    }

    /**
     * @param appName   application name (subdirectory under the platform directory, e.g. "AgentSoftware").
     * @param envPrefix environment variable override prefix (null = appName uppercased).
     * @param env       environment variable snapshot (for test injection; null = System.getenv()).
     * @param platform  platform name (for test injection; null = current OS name).
     */
    public PathManager(String appName, String envPrefix, Map<String, String> env, String platform) {
        this.appName = appName;
        this.env = env != null ? env : System.getenv();
        this.platform = platform != null ? platform : System.getProperty("os.name", "linux");
        this.envPrefix = (envPrefix != null ? envPrefix : appName)
                .trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }

    public static PathManager createDefault() {
        return new PathManager("AgentSoftware");
    }

    private boolean isWindows() {
        return platform.toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean isMacos() {
        String p = platform.toLowerCase(Locale.ROOT);
        return p.contains("mac") || p.contains("darwin");
    }

    private String getEnv(String key) {
        String v = env.get(key);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        // Fallback: system properties (-D injection for tests/containers, equivalent to monkeypatch.setenv in Python tests)
        String prop = System.getProperty(key, "");
        return prop == null ? "" : prop;
    }

    private Path home() {
        String h = isWindows() ? getEnv("USERPROFILE") : getEnv("HOME");
        if (!h.isEmpty()) {
            return Paths.get(h);
        }
        return Paths.get(System.getProperty("user.home", "."));
    }

    /** Windows AppData: kind = "Roaming" (APPDATA) / "Local" (LOCALAPPDATA). */
    private Path appdata(String kind) {
        String key = kind.equals("Roaming") ? "APPDATA" : "LOCALAPPDATA";
        String base = getEnv(key);
        if (!base.isEmpty()) {
            return Paths.get(base).resolve(appName);
        }
        return home().resolve("AppData").resolve(kind).resolve(appName);
    }

    public Path configDir() {
        String override = getEnv(envPrefix + "_CONFIG_DIR");
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        if (isWindows()) {
            return appdata("Roaming");
        }
        if (isMacos()) {
            return home().resolve("Library").resolve("Application Support").resolve(appName);
        }
        String xdg = getEnv("XDG_CONFIG_HOME");
        Path base = xdg.isEmpty() ? home().resolve(".config") : Paths.get(xdg);
        return base.resolve(appName);
    }

    public Path dataDir() {
        String override = getEnv(envPrefix + "_DATA_DIR");
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        if (isWindows()) {
            return appdata("Local");
        }
        if (isMacos()) {
            return home().resolve("Library").resolve("Application Support").resolve(appName);
        }
        String xdg = getEnv("XDG_DATA_HOME");
        Path base = xdg.isEmpty() ? home().resolve(".local").resolve("share") : Paths.get(xdg);
        return base.resolve(appName);
    }

    public Path cacheDir() {
        String override = getEnv(envPrefix + "_CACHE_DIR");
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        if (isWindows()) {
            return dataDir().resolve("Cache");
        }
        if (isMacos()) {
            return home().resolve("Library").resolve("Caches").resolve(appName);
        }
        String xdg = getEnv("XDG_CACHE_HOME");
        Path base = xdg.isEmpty() ? home().resolve(".cache") : Paths.get(xdg);
        return base.resolve(appName);
    }

    public Path logDir() {
        String override = getEnv(envPrefix + "_LOG_DIR");
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        if (isWindows()) {
            return dataDir().resolve("Logs");
        }
        if (isMacos()) {
            return home().resolve("Library").resolve("Logs").resolve(appName);
        }
        String xdg = getEnv("XDG_STATE_HOME");
        Path base = xdg.isEmpty() ? home().resolve(".local").resolve("state") : Paths.get(xdg);
        return base.resolve(appName);
    }

    public Path configFile(String... parts) {
        return resolve(configDir(), parts);
    }

    public Path dataFile(String... parts) {
        return resolve(dataDir(), parts);
    }

    public Path cacheFile(String... parts) {
        return resolve(cacheDir(), parts);
    }

    public Path logFile(String... parts) {
        return resolve(logDir(), parts);
    }

    private static Path resolve(Path base, String[] parts) {
        Path p = base;
        for (String part : parts) {
            p = p.resolve(part);
        }
        return p;
    }

    /** Create all four directories (parents + exist_ok), idempotent. */
    public void ensureDirs() {
        try {
            for (Path d : new Path[]{configDir(), dataDir(), cacheDir(), logDir()}) {
                java.nio.file.Files.createDirectories(d);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create application directories", e);
        }
    }

    /** Create the parent directory of path, return the original path (call before writing a file). */
    public Path ensure(Path path) {
        try {
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create directory: " + path.getParent(), e);
        }
        return path;
    }

    public String getAppName() {
        return appName;
    }
}
