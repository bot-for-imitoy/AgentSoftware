package com.agent.software.tools.spi;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;

/**
 * Factory helpers for defining tools inline inside a toolkit.
 */
public final class Tools {

    private Tools() {
    }

    /** Tool body: receives the owning role and the call, returns a result. */
    @FunctionalInterface
    public interface Handler {
        ToolResult handle(RoleId role, ToolCall call);
    }

    public static Tool of(String name, String description, JsonSchema schema, Handler handler) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        JsonSchema effective = schema == null ? JsonSchema.object() : schema;
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description == null ? "" : description;
            }

            @Override
            public JsonSchema schema() {
                return effective;
            }

            @Override
            public ToolResult execute(RoleId role, ToolCall call) {
                return handler.handle(role, call);
            }
        };
    }

    /** String argument or empty string. */
    public static String arg(ToolCall call, String key) {
        return call.arguments().str(key, "");
    }

    /** Stripped string argument or empty string. */
    public static String argStripped(ToolCall call, String key) {
        return arg(call, key).strip();
    }

    /** Integer argument or a default. */
    public static int intArg(ToolCall call, String key, int def) {
        return call.arguments().intVal(key, def);
    }
}
