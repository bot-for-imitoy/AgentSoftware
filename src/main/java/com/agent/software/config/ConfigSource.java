package com.agent.software.config;

import java.util.Optional;

/**
 * A source of configuration values keyed by an environment-variable-style name.
 *
 * <p>The refactor plan v2 unifies configuration on a single precedence chain:
 * <b>environment variable &gt; config.json &gt; code default</b>. {@code -D} system
 * properties and the legacy {@code OPENAI_*} keys are intentionally not consulted.
 */
@FunctionalInterface
public interface ConfigSource {

    /** Returns the raw value for {@code key}, or empty when the source does not define it. */
    Optional<String> get(String key);

    /** A source that never provides a value. */
    static ConfigSource empty() {
        return key -> Optional.empty();
    }

    /** The process environment. */
    static ConfigSource systemEnvironment() {
        return key -> Optional.ofNullable(System.getenv(key));
    }
}
