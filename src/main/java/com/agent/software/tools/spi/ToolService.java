package com.agent.software.tools.spi;

import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolPort;
import com.agent.software.ports.ToolResult;
import com.agent.software.ports.ToolSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Assembles per-role tools from toolkits and dispatches calls.
 *
 * <p>Implements {@link ToolPort} so the runtime only ever sees specs and results.
 * Bindings are per role instance, which keeps multiple {@code Application}s in one
 * JVM isolated.
 */
public final class ToolService implements ToolPort {

    private record Binding(Map<String, Tool> tools, List<ToolSpec> specs) {
    }

    private final Map<RoleId, Binding> bindings = new ConcurrentHashMap<>();

    /** Bind the toolkits declared by a role. Replaces any previous binding. */
    public void bind(RoleId role, List<Toolkit> toolkits) {
        Map<String, Tool> tools = new LinkedHashMap<>();
        List<ToolSpec> specs = new ArrayList<>();
        for (Toolkit toolkit : toolkits) {
            for (Tool tool : toolkit.tools()) {
                tools.put(tool.name(), tool);
                specs.add(new ToolSpec(tool.name(), tool.description(), tool.schema()));
            }
        }
        bindings.put(role, new Binding(Map.copyOf(tools), List.copyOf(specs)));
    }

    public void unbind(RoleId role) {
        bindings.remove(role);
    }

    public int boundRoleCount() {
        return bindings.size();
    }

    @Override
    public List<ToolSpec> specs(RoleId role) {
        Binding binding = bindings.get(role);
        return binding == null ? List.of() : binding.specs();
    }

    @Override
    public ToolResult invoke(RoleId role, ToolCall call) {
        Binding binding = bindings.get(role);
        if (binding == null) {
            return ToolResult.error("Error: no tools are bound for role '" + role + "'");
        }
        Tool tool = binding.tools().get(call.name());
        if (tool == null) {
            return ToolResult.error("Error: tool '" + call.name() + "' not found. Available: "
                    + binding.tools().keySet());
        }
        try {
            ToolResult result = tool.execute(role, call);
            return result == null ? ToolResult.error("Tool '" + call.name() + "' returned no result") : result;
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.error("Tool error: " + message);
        }
    }
}
