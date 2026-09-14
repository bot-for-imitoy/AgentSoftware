package com.agent.software.kernel;

/**
 * Base unchecked exception for the agent runtime.
 *
 * <p>The refactor plan v2 replaces the historical mix of
 * {@code IllegalArgumentException}/{@code IllegalStateException}/
 * {@code RuntimeException} with three explicit failure classes so callers can
 * react to the layer that failed:
 * <ul>
 *   <li>{@link ConfigException} — bad configuration (fail fast at bootstrap);</li>
 *   <li>{@link PortException} — an external adapter failed (LLM, computer, MCP, mail);</li>
 *   <li>{@link DomainException} — a domain rule was violated.</li>
 * </ul>
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Invalid or missing configuration. */
    public static class ConfigException extends AgentException {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** An external port/adapter failed. */
    public static class PortException extends AgentException {
        public PortException(String message) {
            super(message);
        }

        public PortException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A domain invariant was violated. */
    public static class DomainException extends AgentException {
        public DomainException(String message) {
            super(message);
        }

        public DomainException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
