package com.agent.software.tools.spi;

import java.util.List;

/**
 * A named group of {@link Tool}s, declared by id in {@code role_templates.json}.
 */
public record Toolkit(String id, String description, List<Tool> tools) {

    public Toolkit {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Toolkit id must not be blank");
        }
        id = id.strip();
        description = description == null ? "" : description;
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
