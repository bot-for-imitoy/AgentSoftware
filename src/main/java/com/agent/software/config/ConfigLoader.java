package com.agent.software.config;

import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loads {@link AppConfig} with a single precedence chain:
 * <b>environment variable &gt; config.json &gt; code default</b>.
 *
 * <p>A dotted configuration path maps to an environment variable by upper-casing
 * it, replacing {@code .} with {@code _} and inserting {@code _} before camel-case
 * humps: {@code llm.apiKeys} becomes
 * {@code AGENTSOFTWARE_LLM_API_KEYS}. {@code -D} system properties are not
 * consulted, and the legacy {@code OPENAI_*} names are gone.
 */
public final class ConfigLoader {

    public static final String ENV_PREFIX = "AGENTSOFTWARE_";

    private final Path configFile;
    private final ConfigSource env;
    private final Map<String, Object> file;

    private ConfigLoader(Path configFile, ConfigSource env, Map<String, Object> file) {
        this.configFile = configFile;
        this.env = env != null ? env : ConfigSource.empty();
        this.file = file != null ? file : Map.of();
    }

    /** Load using the process environment. */
    public static ConfigLoader load(Path configFile) {
        return load(configFile, ConfigSource.systemEnvironment());
    }

    /** Load using an explicit environment source (used by tests). */
    public static ConfigLoader load(Path configFile, ConfigSource env) {
        return new ConfigLoader(configFile, env, readFile(configFile));
    }

    /** The config file that was consulted (may not exist). */
    public Path configFile() {
        return configFile;
    }

    /** Derive the environment variable name for a dotted configuration path. */
    public static String envKey(String path) {
        StringBuilder sb = new StringBuilder(ENV_PREFIX);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                sb.append('_');
                continue;
            }
            if (Character.isUpperCase(c) && i > 0 && path.charAt(i - 1) != '.') {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /** Resolve every configuration slice with the documented precedence. */
    public AppConfig toAppConfig() {
        AppConfig d = AppConfig.defaults();
        return new AppConfig(
                new AppConfig.Llm(
                        str("llm.provider", d.llm().provider()),
                        str("llm.model", d.llm().model()),
                        strMap("llm.apiKeys", d.llm().apiKeys()),
                        new AppConfig.Llm.Retry(
                                integer("llm.retry.maxAttempts", d.llm().retry().maxAttempts()),
                                decimal("llm.retry.delaySeconds", d.llm().retry().delaySeconds()),
                                integer("llm.retry.timeoutSeconds", d.llm().retry().timeoutSeconds()))),
                new AppConfig.Schedule(
                        decimal("schedule.secondsPerTick", d.schedule().secondsPerTick()),
                        decimal("schedule.simSecondsPerRealSecond", d.schedule().simSecondsPerRealSecond()),
                        integer("schedule.shiftStartHour", d.schedule().shiftStartHour()),
                        integer("schedule.shiftEndHour", d.schedule().shiftEndHour()),
                        decimal("schedule.fastForwardIdleSeconds", d.schedule().fastForwardIdleSeconds()),
                        decimal("schedule.wrapUpGraceSeconds", d.schedule().wrapUpGraceSeconds())),
                new AppConfig.Computer(
                        str("computer.defaultKind", d.computer().defaultKind()),
                        str("computer.network", d.computer().network()),
                        str("computer.image", d.computer().image()),
                        str("computer.containerfile", d.computer().containerfile())),
                new AppConfig.Mail(
                        str("mail.suffix", d.mail().suffix()),
                        str("mail.dataDir", d.mail().dataDir()),
                        new AppConfig.Mail.Smtp(
                                str("mail.smtp.host", d.mail().smtp().host()),
                                integer("mail.smtp.port", d.mail().smtp().port()),
                                str("mail.smtp.user", d.mail().smtp().user()),
                                str("mail.smtp.password", d.mail().smtp().password()),
                                str("mail.smtp.from", d.mail().smtp().from()),
                                flagRaw("mail.smtp.useSsl").orElse(d.mail().smtp().useSsl()))),
                new AppConfig.Web(
                        str("web.host", d.web().host()),
                        integer("web.port", d.web().port()),
                        longVal("web.replyTimeoutMs", d.web().replyTimeoutMs())),
                new AppConfig.Storage(
                        str("storage.dataDir", d.storage().dataDir()),
                        str("storage.configDir", d.storage().configDir()),
                        str("storage.cacheDir", d.storage().cacheDir()),
                        str("storage.logDir", d.storage().logDir())),
                new AppConfig.Toolkits(strList("toolkits.default", d.toolkits().defaults())));
    }

    // ── typed accessors ────────────────────────────────────────────────

    public String str(String path, String def) {
        return raw(path).orElse(def);
    }

    public int integer(String path, int def) {
        Optional<String> v = raw(path);
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.get().strip());
        } catch (NumberFormatException e) {
            throw new AgentException.ConfigException(
                    "config '" + path + "' must be an integer, got: " + v.get());
        }
    }

    public long longVal(String path, long def) {
        Optional<String> v = raw(path);
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(v.get().strip());
        } catch (NumberFormatException e) {
            throw new AgentException.ConfigException(
                    "config '" + path + "' must be a long, got: " + v.get());
        }
    }

    public double decimal(String path, double def) {
        Optional<String> v = raw(path);
        if (v.isEmpty()) {
            return def;
        }
        try {
            return Double.parseDouble(v.get().strip());
        } catch (NumberFormatException e) {
            throw new AgentException.ConfigException(
                    "config '" + path + "' must be a number, got: " + v.get());
        }
    }

    public boolean flag(String path, boolean def) {
        return flagRaw(path).orElse(def);
    }

    public Optional<Boolean> flagRaw(String path) {
        return raw(path).map(v -> parseFlag(path, v));
    }

    public List<String> strList(String path, List<String> def) {
        Optional<String> envValue = nonBlank(env.get(envKey(path)));
        if (envValue.isPresent()) {
            return splitList(envValue.get());
        }
        Object v = Json.getByPath(file, path, null);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return List.copyOf(out);
        }
        if (v instanceof String s && !s.isBlank()) {
            return splitList(s);
        }
        return def;
    }

    public Map<String, String> strMap(String path, Map<String, String> def) {
        Optional<String> envValue = nonBlank(env.get(envKey(path)));
        if (envValue.isPresent()) {
            return parsePairs(envValue.get());
        }
        Object v = Json.getByPath(file, path, null);
        if (v instanceof Map<?, ?> m) {
            Map<String, String> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            return Map.copyOf(out);
        }
        return def;
    }

    // ── internals ──────────────────────────────────────────────────────

    private Optional<String> raw(String path) {
        Optional<String> envValue = nonBlank(env.get(envKey(path)));
        if (envValue.isPresent()) {
            return envValue;
        }
        Object v = Json.getByPath(file, path, null);
        if (v == null) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(v));
    }

    private static Optional<String> nonBlank(Optional<String> value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String v = value.get();
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
    }

    private static List<String> splitList(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String v = part.strip();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, String> parsePairs(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : raw.split(",")) {
            String entry = part.strip();
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new AgentException.ConfigException(
                        "expected 'key=value' entries, got: " + entry);
            }
            out.put(entry.substring(0, eq).strip(), entry.substring(eq + 1).strip());
        }
        return Map.copyOf(out);
    }

    private static boolean parseFlag(String path, String raw) {
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> throw new AgentException.ConfigException(
                    "config '" + path + "' must be a boolean, got: " + raw);
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return Map.of();
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentException.ConfigException("cannot read config file: " + path, e);
        }
        if (text.isBlank()) {
            return Map.of();
        }
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (IOException e) {
            throw new AgentException.ConfigException("config file is not valid JSON: " + path, e);
        }
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new AgentException.ConfigException("config file root must be a JSON object: " + path);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (e.getKey() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }
}
