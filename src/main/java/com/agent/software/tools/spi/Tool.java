package com.agent.software.tools.spi;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;

/**
 * A capability the model may invoke.
 *
 * <p>Replaces the legacy {@code tools.Tool}: the schema is typed
 * ({@link JsonSchema} with {@code required}/enums), execution receives the owning
 * {@link RoleId}, and implementations depend on ports rather than on
 * {@code AgentRole}.
 */
public interface Tool {

    String name();

    String description();

    JsonSchema schema();

    ToolResult execute(RoleId role, ToolCall call);
}
