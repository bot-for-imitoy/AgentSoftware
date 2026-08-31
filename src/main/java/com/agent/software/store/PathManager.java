package com.agent.software.store;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

/**
 * PathManager — 跨平台应用路径管理 (Python 版 path_manager.py).
 *
 * 按各平台目录约定返回应用专属目录, 作为全项目路径的唯一入口.
 * 环境变量显式覆盖 (优先级最高): {@code <ENV_PREFIX>_CONFIG_DIR / _DATA_DIR /
 * _CACHE_DIR / _LOG_DIR}; 默认 ENV_PREFIX = app_name 大写化 (下划线).
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
     * @param appName   应用名 (平台目录下的子目录, 如 "AgentCompany").
     * @param envPrefix 环境变量覆盖前缀 (null = appName 大写化).
     * @param env       环境变量快照 (测试注入用; null = System.getenv()).
     * @param platform  平台名 (测试注入用; null = 当前 OS 名).
     */
    public PathManager(String appName, String envPrefix, Map<String, String> env, String platform) {
        this.appName = appName;
        this.env = env != null ? env : System.getenv();
        this.platform = platform != null ? platform : System.getProperty("os.name", "linux");
        this.envPrefix = (envPrefix != null ? envPrefix : appName)
                .trim().toUpperCase(Locale.ROOT).replace("-", "_");
    }

    public static PathManager createDefault() {
        return new PathManager("AgentCompany");
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
        // 回退: 系统属性 (测试/容器用 -D 注入, 等价于 Python 测试的 monkeypatch.setenv)
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

    /** 创建全部四个目录 (parents + exist_ok), 幂等. */
    public void ensureDirs() {
        try {
            for (Path d : new Path[]{configDir(), dataDir(), cacheDir(), logDir()}) {
                java.nio.file.Files.createDirectories(d);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("创建应用目录失败", e);
        }
    }

    /** 创建 path 的父目录, 返回原 path (写文件前调用). */
    public Path ensure(Path path) {
        try {
            if (path.getParent() != null) {
                java.nio.file.Files.createDirectories(path.getParent());
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("创建目录失败: " + path.getParent(), e);
        }
        return path;
    }

    public String getAppName() {
        return appName;
    }
}
