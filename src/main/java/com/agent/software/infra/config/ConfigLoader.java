package com.agent.software.infra.config;

import com.agent.software.infra.json.JacksonJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 唯一配置入口，优先级 env &gt; config.json &gt; 代码默认。
 *
 * <p>config.json 位于配置目录（见 {@link AppPaths}），结构为嵌套对象；
 * 环境变量（或同名 {@code -D} 系统属性）覆盖其中少数几个运行期开关。
 */
public final class ConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigLoader.class);

    private final JacksonJsonCodec json = new JacksonJsonCodec();

    /** 加载并按优先级合并三层配置。 */
    public AppConfig load() {
        AppConfig base = AppConfig.defaults();
        AppPaths paths = AppPaths.resolve(base.storage());
        Map<String, Object> file = readConfigFile(paths);

        AppConfig.Llm llm = new AppConfig.Llm(
                str(file, "llm.provider", str(file, "llm.provider_id", base.llm().providerId())),
                str(file, "llm.model", base.llm().model()),
                str(file, "llm.embedding_model", base.llm().embeddingModel()),
                str(file, "llm.api_key", str(file, "llm.apiKey", base.llm().apiKey())),
                str(file, "llm.base_url", str(file, "llm.baseUrl", base.llm().baseUrl())),
                intVal(file, "llm.max_context_messages", base.llm().maxContextMessages()),
                new AppConfig.Llm.Retry(
                        intVal(file, "llm.retry.max_attempts", base.llm().retry().maxAttempts()),
                        doubleVal(file, "llm.retry.delay_seconds", base.llm().retry().delaySeconds()),
                        intVal(file, "llm.retry.timeout_seconds", base.llm().retry().timeoutSeconds())));

        AppConfig.Schedule schedule = new AppConfig.Schedule(
                num(file, "schedule.seconds_per_tick", "AGENTSOFTWARE_SECONDS_PER_TICK",
                        "agentsoftware.secondsPerTick", base.schedule().secondsPerTick()),
                intVal(file, "schedule.shift_start_hour", base.schedule().shiftStartHour()),
                intVal(file, "schedule.shift_end_hour", base.schedule().shiftEndHour()),
                longVal(file, "schedule.fast_forward_idle_millis", base.schedule().fastForwardIdleMillis()),
                num(file, "schedule.sim_seconds_per_real_second", "AGENTSOFTWARE_SIM_SECONDS_PER_REAL_SECOND",
                        "agentsoftware.simSecondsPerRealSecond", base.schedule().simSecondsPerRealSecond()),
                longVal(file, "schedule.wrap_up_grace_millis", base.schedule().wrapUpGraceMillis()));

        AppConfig.Storage storage = new AppConfig.Storage(str(file, "storage.data_dir", base.storage().dataDir()));

        AppConfig.Web web = new AppConfig.Web(
                override(file, "web.host", "AGENTSOFTWARE_WEB_HOST", "agentsoftware.web.host", base.web().host()),
                intOverride(file, "web.port", "AGENTSOFTWARE_WEB_PORT", "agentsoftware.web.port", base.web().port()),
                longOverride(file, "web.reply_timeout_millis", "AGENTSOFTWARE_CLIENT_REPLY_TIMEOUT",
                        "agentsoftware.clientReplyTimeout", base.web().replyTimeoutMillis()));

        AppConfig.Mail mail = new AppConfig.Mail(
                str(file, "mail.suffix", base.mail().suffix()),
                new AppConfig.Mail.Smtp(
                        str(file, "mail.smtp.host", base.mail().smtp().host()),
                        intVal(file, "mail.smtp.port", base.mail().smtp().port()),
                        str(file, "mail.smtp.user", base.mail().smtp().user()),
                        str(file, "mail.smtp.password", base.mail().smtp().password()),
                        str(file, "mail.smtp.from", base.mail().smtp().from()),
                        boolVal(file, "mail.smtp.use_ssl", base.mail().smtp().useSsl())));

        Set<String> toolkits = strList(file, "toolkits.defaults");
        AppConfig.Toolkits toolkitConfig = new AppConfig.Toolkits(
                toolkits.isEmpty() ? base.toolkits().defaults() : toolkits);

        return new AppConfig(llm, schedule, storage, web, mail, toolkitConfig);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readConfigFile(AppPaths paths) {
        Path cfg = paths.configFile("config.json");
        if (!Files.exists(cfg)) {
            return new LinkedHashMap<>();
        }
        try {
            return json.readMap(Files.readString(cfg));
        } catch (Exception e) {
            logger.warn("配置文件无法解析，回退到默认配置: {} ({})", cfg, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    // ── 取值助手（支持点路径） ──────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Object raw(Map<String, Object> root, String dotPath) {
        Object current = root;
        for (String part : dotPath.split("\\.")) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    private static String str(Map<String, Object> root, String key, String def) {
        Object v = raw(root, key);
        return v == null ? def : String.valueOf(v);
    }

    private static int intVal(Map<String, Object> root, String key, int def) {
        Object v = raw(root, key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return (int) Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static long longVal(Map<String, Object> root, String key, long def) {
        Object v = raw(root, key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v != null) {
            try {
                return (long) Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static double doubleVal(Map<String, Object> root, String key, double def) {
        Object v = raw(root, key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    private static boolean boolVal(Map<String, Object> root, String key, boolean def) {
        Object v = raw(root, key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (s) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> def;
        };
    }

    private static Set<String> strList(Map<String, Object> root, String key) {
        Object v = raw(root, key);
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    // ── 环境变量 / 系统属性覆盖 ────────────────────────────────

    private static String env(String envKey, String propKey) {
        String v = System.getenv(envKey);
        if (v != null && !v.isBlank()) {
            return v.trim();
        }
        String p = System.getProperty(propKey);
        return p == null || p.isBlank() ? null : p.trim();
    }

    private static String override(Map<String, Object> root, String key, String envKey, String propKey, String def) {
        String e = env(envKey, propKey);
        return e != null ? e : str(root, key, def);
    }

    private static int intOverride(Map<String, Object> root, String key, String envKey, String propKey, int def) {
        String e = env(envKey, propKey);
        if (e != null) {
            try {
                return Integer.parseInt(e);
            } catch (NumberFormatException ignored) {
                // 落回配置文件 / 默认值
            }
        }
        return intVal(root, key, def);
    }

    private static long longOverride(Map<String, Object> root, String key, String envKey, String propKey, long def) {
        String e = env(envKey, propKey);
        if (e != null) {
            try {
                return Long.parseLong(e);
            } catch (NumberFormatException ignored) {
                // 落回配置文件 / 默认值
            }
        }
        return longVal(root, key, def);
    }

    private static double num(Map<String, Object> root, String key, String envKey, String propKey, double def) {
        String e = env(envKey, propKey);
        if (e != null) {
            try {
                return Double.parseDouble(e);
            } catch (NumberFormatException ignored) {
                // 落回配置文件 / 默认值
            }
        }
        return doubleVal(root, key, def);
    }
}
