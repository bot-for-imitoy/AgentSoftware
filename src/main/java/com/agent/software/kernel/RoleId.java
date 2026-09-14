package com.agent.software.kernel;

/**
 * Strongly typed role identifier.
 *
 * <p>Introduced by the refactor plan v2: role/task/event ids must not be freely
 * interchangeable {@code String}s. All runtime and tool APIs take {@code RoleId}
 * (or {@code TaskId}/{@code EventId}) instead of raw strings.
 */
public record RoleId(String value) {

    public RoleId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("RoleId must not be blank");
        }
        value = value.strip();
    }

    public static RoleId of(String value) {
        return new RoleId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
