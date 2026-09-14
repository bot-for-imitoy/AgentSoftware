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
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Assembles per-role tools from toolkits and dispatches calls.
 *
 * <p>Implements {@link ToolPort} so the runtime only ever sees specs and results.
 * Bindings are per role instance, which keeps multiple {@code Application}s in one
 * JVM isolated. Tools may also be added or removed at runtime (MCP/skill
 * self-service).
 */
public final class ToolService implements ToolPort {

    private static final class Binding {
        private final Map<String, Tool> tools = new ConcurrentHashMap<>();
        private final List<ToolSpec> specs = new CopyOnWriteArrayList<>();
    }

    private final Map<RoleId, Binding> bindings = new ConcurrentHashMap<>();

    /** Bind the toolkits declared by a role. Replaces any previous binding. */
    public void bind(RoleId role, List<Toolkit> toolkits) {
        Binding binding = new Binding();
        for (Toolkit toolkit : toolkits) {
            for (Tool tool : toolkit.tools()) {
                binding.tools.put(tool.name(), tool);
                binding.specs.add(new ToolSpec(tool.name(), tool.description(), tool.schema()));
            }
        }
        bindings.put(role, binding);
    }

    public void unbind(RoleId role) {
        bindings.remove(role);
    }

    /** Register (or replace) a single tool for a role at runtime. */
    public void addTool(RoleId role, ToolSpec spec, Tools.Handler handler) {
        Binding binding = bindings.get(role);
        if (binding == null) {
            throw new IllegalStateException("role is not bound to the tool service: " + role);
        }
        binding.tools.put(spec.name(), Tools.of(spec.name(), spec.description(), spec.schema(), handler));
        binding.specs.removeIf(existing -> existing.name().equals(spec.name()));
        binding.specs.add(spec);
    }

    /** Remove a single tool; returns whether it existed. */
    public boolean removeTool(RoleId role, String name) {
        Binding binding = bindings.get(role);
        if (binding == null) {
            return false;
        }
        boolean existed = binding.tools.remove(name) != null;
        binding.specs.removeIf(spec -> spec.name().equals(name));
        return existed;
    }

    public boolean hasTool(RoleId role, String name) {
        Binding binding = bindings.get(role);
        return binding != null && binding.tools.containsKey(name);
    }

    public List<String> toolNames(RoleId role) {
        Binding binding = bindings.get(role);
        return binding == null ? List.of() : new ArrayList<>(binding.tools.keySet());
    }

    public int boundRoleCount() {
        return bindings.size();
    }

    @Override
    public List<ToolSpec> specs(RoleId role) {
        Binding binding = bindings.get(role);
        return binding == null ? List.of() : List.copyOf(binding.specs);
    }

    @Override
    public ToolResult invoke(RoleId role, ToolCall call) {
        Binding binding = bindings.get(role);
        if (binding == null) {
            return ToolResult.error("Error: no tools are bound for role '" + role + "'");
        }
        Tool tool = binding.tools.get(call.name());
        if (tool == null) {
            return ToolResult.error("Error: tool '" + call.name() + "' not found. Available: "
                    + new LinkedHashMap<>(binding.tools).keySet());
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
