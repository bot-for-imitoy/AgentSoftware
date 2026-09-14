package com.agent.software.ports;

import com.agent.software.kernel.JsonSchema;

/**
 * Externally visible description of one callable tool.
 *
 * <p>Carries a typed {@link JsonSchema} (with {@code required} and enums) instead
 * of the legacy flat string map.
 */
public record ToolSpec(String name, String description, JsonSchema schema) {

    public ToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolSpec name must not be blank");
        }
        description = description == null ? "" : description;
        schema = schema == null ? JsonSchema.object() : schema;
    }
}
