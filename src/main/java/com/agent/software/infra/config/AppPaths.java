package com.agent.software.infra.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import com.agent.software.infra.config.AppConfig.Storage;

/**
 * 所有磁盘路径的唯一来源：解析数据/配置目录并按需创建父目录（XDG/Windows/macOS）。
 *
 * <p>对应 master {@code store.PathManager}：应用名固定为 {@code AgentSoftware}，
 * 环境变量前缀 {@code AGENTSOFTWARE_}（同时接受同名 {@code -D} 系统属性）。
 */
public final class AppPaths {

    /** 应用名（平台目录下的子目录）。 */
    public static final String APP_NAME = "AgentSoftware";

    private static final String ENV_PREFIX = "AGENTSOFTWARE";

    private final Path dataDir;
    private final Path configDir;

    private AppPaths(Path dataDir, Path configDir) {
        this.dataDir = dataDir;
        this.configDir = configDir;
    }

    /** 由存储配置解析出跨平台路径集合。 */
    public static AppPaths resolve(AppConfig.Storage storage) {
        String configured = storage == null ? "" : trimToEmpty(storage.dataDir());
        Path data = configured.isEmpty() ? defaultDataDir() : Paths.get(configured);
        return new AppPaths(data, defaultConfigDir());
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        return trimToEmpty(System.getProperty(key));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "linux").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        String p = System.getProperty("os.name", "linux").toLowerCase(Locale.ROOT);
        return p.contains("mac") || p.contains("darwin");
    }

    private static Path home() {
        String h = System.getProperty("user.home", "");
        return h.isEmpty() ? Paths.get(".") : Paths.get(h);
    }

    private static Path defaultDataDir() {
        String override = env(ENV_PREFIX + "_DATA_DIR");
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        if (isWindows()) {
            String base = env("LOCALAPPDATA");
            return (base.isEmpty() ? home().resolve("AppData").resolve("Local") : Paths.get(base))
                    .resolve(APP_NAME);
        }
        if (isMac()) {
            return home().resolve("Library").resolve("Application Support").resolve(APP_NAME);
        }
        String xdg = env("XDG_DATA_HOME");
        Path base = xdg.isEmpty() ? home().resolve(".local").resolve("share") : Paths.get(xdg);
        return base.resolve(APP_NAME);
    }

    private static Path defaultConfigDir() {
        String override = env(ENV_PREFIX + "_CONFIG_DIR");
        if (!override.isEmpty()) {
            return Paths.get(override);
        }
        if (isWindows()) {
            String base = env("APPDATA");
            return (base.isEmpty() ? home().resolve("AppData").resolve("Roaming") : Paths.get(base))
                    .resolve(APP_NAME);
        }
        if (isMac()) {
            return home().resolve("Library").resolve("Application Support").resolve(APP_NAME);
        }
        String xdg = env("XDG_CONFIG_HOME");
        Path base = xdg.isEmpty() ? home().resolve(".config") : Paths.get(xdg);
        return base.resolve(APP_NAME);
    }

    /** 数据根目录。 */
    public Path dataDir() {
        return dataDir;
    }

    /** 配置根目录。 */
    public Path configDir() {
        return configDir;
    }

    /** 数据根目录下拼接出的文件路径。 */
    public Path dataFile(String... parts) {
        return resolve(dataDir, parts);
    }

    /** 配置目录下拼接出的文件路径。 */
    public Path configFile(String... parts) {
        return resolve(configDir, parts);
    }

    private static Path resolve(Path base, String[] parts) {
        Path p = base;
        if (parts != null) {
            for (String part : parts) {
                p = p.resolve(part);
            }
        }
        return p;
    }

    /** 确保路径的父目录存在并返回该路径。 */
    public Path ensure(Path path) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (java.io.IOException e) {
            throw new com.agent.software.kernel.DomainError("paths.mkdir.failed",
                    "无法创建目录: " + path.getParent(), e);
        }
        return path;
    }
}
