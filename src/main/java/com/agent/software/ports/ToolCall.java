package com.agent.software.ports;

import com.agent.software.domain.Payload;

/** One tool invocation requested by the model or by the runtime. */
public record ToolCall(String id, String name, Payload arguments) {

    public ToolCall {
        id = id == null ? "" : id;
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ToolCall name must not be blank");
        }
        arguments = arguments == null ? Payload.empty() : arguments;
    }

    public static ToolCall of(String name, Payload arguments) {
        return new ToolCall("", name, arguments);
    }
}
