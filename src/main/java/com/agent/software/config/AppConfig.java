package com.agent.software.config;

import java.util.List;
import java.util.Map;

/**
 * Immutable, strongly typed application configuration.
 *
 * <p>Built once at bootstrap by {@link ConfigLoader}. Every runtime component
 * receives the slice it needs through its constructor instead of reading the
 * environment itself.
 */
public record AppConfig(
        Llm llm,
        Schedule schedule,
        Computer computer,
        Mail mail,
        Web web,
        Storage storage,
        Toolkits toolkits) {

    /** LLM provider selection and retry policy. */
    public record Llm(String provider, String model, Map<String, String> apiKeys, Retry retry) {
        public Llm {
            apiKeys = apiKeys == null ? Map.of() : Map.copyOf(apiKeys);
        }

        /** HTTP retry policy for the LLM endpoint. */
        public record Retry(int maxAttempts, double delaySeconds, int timeoutSeconds) {
        }
    }

    /** Simulated-shift clock configuration. */
    public record Schedule(
            double secondsPerTick,
            double simSecondsPerRealSecond,
            int shiftStartHour,
            int shiftEndHour,
            double fastForwardIdleSeconds,
            double wrapUpGraceSeconds) {
    }

    /** Personal-computer configuration (podman/ssh/local all supported). */
    public record Computer(String defaultKind, String network, String image, String containerfile) {
    }

    /** Company-mail configuration. */
    public record Mail(String suffix, String dataDir, Smtp smtp) {
        /** Real-SMTP settings; an empty host keeps delivery virtual. */
        public record Smtp(String host, int port, String user, String password, String from, Boolean useSsl) {
        }
    }

    /** Web UI / client-reply configuration. */
    public record Web(String host, int port, long replyTimeoutMs) {
    }

    /** Filesystem roots; {@code null} means "derive from the platform default". */
    public record Storage(String dataDir, String configDir, String cacheDir, String logDir) {
    }

    /** Toolkits available to roles that do not declare their own list. */
    public record Toolkits(List<String> defaults) {
        public Toolkits {
            defaults = defaults == null ? List.of() : List.copyOf(defaults);
        }
    }

    /** Code-level defaults; the lowest precedence layer. */
    public static AppConfig defaults() {
        return new AppConfig(
                new Llm("openai", null, Map.of(), new Llm.Retry(200, 10.0, 120)),
                new Schedule(1.0, 1.0, 8, 18, 60.0, 600.0),
                new Computer("podman", "maf-net", "maf-base:latest", "Containerfile"),
                new Mail("company.com", "data/mail", new Mail.Smtp("", 587, "", "", "", null)),
                new Web("0.0.0.0", 8787, 1_200_000L),
                new Storage("data", null, null, null),
                new Toolkits(List.of(
                        "memory", "note", "time", "todo", "task_view",
                        "pc", "mcp", "skill", "email", "talk")));
    }
}
