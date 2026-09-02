package com.agent.software.role;

import com.agent.software.core.MCPServer;
import com.agent.software.tools.Tool;
import com.agent.software.tools.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP + Python Tool System (Java counterpart of the Python tools.py).
 *
 * Two-layer architecture:
 *   ToolKit      — a named collection of related tools (MCP or Python native).
 *   ToolRegistry — per-role tool registry, managing ToolKits.
 */
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    /** Tool handler: receives an argument dictionary, returns a string result (Python ToolHandler semantics). */
    @FunctionalInterface
    public interface ToolHandler {
        String handle(Map<String, Object> args);
    }

    /** Unified tool definition — shared by MCP and Python native tools. */
    public static final class ToolDef {
        public String name;
        public String description = "";
        public Map<String, Object> inputSchema = new LinkedHashMap<>();
        public ToolHandler handler = null;   // Python-native handler
        public String source = "python";     // "python" | "mcp" | "talk"
        public Object mcpTool = null;        // Original MCP Tool object if applicable

        public ToolDef(String name, String description, Map<String, Object> inputSchema,
                       ToolHandler handler, String source) {
            this.name = name;
            this.description = description != null ? description : "";
            this.inputSchema = inputSchema != null ? inputSchema : new LinkedHashMap<>();
            this.handler = handler;
            this.source = source != null ? source : "python";
        }
    }

    /** Text content of an MCP tool result (a component of CallToolResult). */
    public static final class TextContent {
        public String type = "text";
        public String text = "";

        public TextContent(String text) {
            this.text = text;
        }
    }

    /** MCP tool call result. */
    public static final class CallToolResult {
        public List<TextContent> content = new ArrayList<>();
        public boolean isError = false;

        public CallToolResult(List<TextContent> content, boolean isError) {
            this.content = content;
            this.isError = isError;
        }
    }

    /** A group of related tools (Python native / MCP / mixed). */
    public static final class ToolKit {
        public String name;
        public String description;
        private final Map<String, ToolDef> tools = new LinkedHashMap<>();
        /** Toolkit context bindings (role / manager / store, etc.). */
        private final Map<String, Object> bindings = new LinkedHashMap<>();

        public ToolKit(String name, String description) {
            this.name = name;
            this.description = description != null ? description : "";
        }

        // ── Context bindings (bind/require) ─────────────────────────

        public void bind(String key, Object value) {
            bindings.put(key, value);
        }

        public Object get(String key, Object def) {
            return bindings.getOrDefault(key, def);
        }

        /** Read a bound context value; throws a clear error if unbound. */
        public Object require(String key, String what) {
            Object v = bindings.get(key);
            if (v == null) {
                throw new RuntimeException(what + " is not bound; register this toolkit via role.addToolkit()");
            }
            return v;
        }

        // ── Python tool management ────────────────────────────

        /** Add a Python native tool. */
        public ToolDef addPythonTool(String name, String description,
                                     Map<String, Object> inputSchema, ToolHandler handler) {
            if (tools.containsKey(name)) {
                throw new IllegalArgumentException("Tool '" + name + "' already exists in toolkit '" + this.name + "'");
            }
            ToolDef td = new ToolDef(name, description, inputSchema, handler, "python");
            tools.put(name, td);
            logger.info("ToolKit[{}] Python tool: {}", this.name, name);
            return td;
        }

        /** Directly add a pre-built ToolDef (source comes from td itself, for MCP tools, etc.). */
        public void addTool(ToolDef td) {
            if (tools.containsKey(td.name)) {
                throw new IllegalArgumentException("Tool '" + td.name + "' already exists in toolkit '" + this.name + "'");
            }
            tools.put(td.name, td);
            logger.info("ToolKit[{}] tool added: {} (source={})", this.name, td.name, td.source);
        }

        /** Remove a tool. Returns whether it existed. */
        public boolean removeTool(String name) {
            return tools.remove(name) != null;
        }

        public List<String> toolNames() {
            return new ArrayList<>(tools.keySet());
        }

        public int toolCount() {
            return tools.size();
        }

        public ToolDef getTool(String name) {
            return tools.get(name);
        }

        public List<ToolDef> allTools() {
            return new ArrayList<>(tools.values());
        }
    }

    // ── ToolRegistry ──────────────────────────────────────

    private final Map<String, ToolDef> tools = new LinkedHashMap<>();    // unified registry
    private final Map<String, ToolKit> toolkits = new LinkedHashMap<>(); // loaded toolkits by name
    private final Map<String, String> toolSource = new LinkedHashMap<>(); // tool_name → toolkit_name

    /** Import an entire toolkit. Returns the number of newly added tools (duplicates skipped). */
    public int addToolkit(ToolKit toolkit) {
        if (toolkits.containsKey(toolkit.name)) {
            logger.warn("Toolkit '{}' already loaded, skipping", toolkit.name);
            return 0;
        }
        toolkits.put(toolkit.name, toolkit);
        int added = 0;
        for (ToolDef td : toolkit.allTools()) {
            if (tools.containsKey(td.name)) {
                String existing = toolSource.getOrDefault(td.name, "unknown");
                logger.warn("Tool '{}' from toolkit '{}' conflicts with existing tool from '{}' — keeping original",
                        td.name, toolkit.name, existing);
                continue;
            }
            tools.put(td.name, td);
            toolSource.put(td.name, toolkit.name);
            added++;
        }
        logger.info("ToolRegistry: loaded toolkit '{}' — {} tools ({} new, {} skipped)",
                toolkit.name, toolkit.toolCount(), added, toolkit.toolCount() - added);
        return added;
    }

    /**
     * Directly import a template-style toolkit (tools.Toolkit / tools.Tool, see toolkits.* implementations).
     * Each Tool is registered as a Python native tool: tool name/description are taken from the Tool,
     * the flat parameter spec is converted from {@link Tool#getInputSchema()} into an OpenAI-style input_schema,
     * and the handler is the Tool's execution method.
     */
    public int addToolkit(Toolkit toolkit) {
        ToolKit tk = new ToolKit(toolkit.getName(), toolkit.getDescription());
        for (Tool tool : toolkit.getTools()) {
            tk.addPythonTool(tool.getToolName(), tool.getDescription(),
                    tool.getInputSchema(), tool::handler);
        }
        return addToolkit(tk);
    }

    /** Remove a toolkit and all of its tools. Returns the number of tools removed. */
    public int removeToolkit(String name) {
        ToolKit tk = toolkits.remove(name);
        if (tk == null) {
            return 0;
        }
        int removed = 0;
        for (ToolDef td : tk.allTools()) {
            if (name.equals(toolSource.get(td.name))) {
                tools.remove(td.name);
                toolSource.remove(td.name);
                removed++;
            }
        }
        return removed;
    }

    /** Register a single tool. */
    public void addTool(String name, String description, Map<String, Object> inputSchema,
                        ToolHandler handler, String source) {
        if (tools.containsKey(name)) {
            logger.warn("Tool '{}' already registered, overwriting", name);
        }
        tools.put(name, new ToolDef(name, description, inputSchema, handler, source));
        toolSource.put(name, source);
        logger.info("Tool registered: {} — {}", name, description != null && description.length() > 60
                ? description.substring(0, 60) : description);
    }

    public void removeTool(String name) {
        tools.remove(name);
        toolSource.remove(name);
    }

    /** Return all tools (LLM-compatible format). */
    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDef td : tools.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", td.name);
            m.put("description", td.description);
            m.put("input_schema", td.inputSchema);
            out.add(m);
        }
        return out;
    }

    /** Execute a tool. Searches all loaded toolkits. */
    public CallToolResult callTool(String name, Map<String, Object> arguments) {
        ToolDef td = tools.get(name);
        if (td == null) {
            return new CallToolResult(List.of(new TextContent(
                    "Error: tool '" + name + "' not found. Available: " + tools.keySet())), true);
        }
        if (td.handler == null) {
            return new CallToolResult(List.of(new TextContent(
                    "Error: tool '" + name + "' is MCP-based and requires an active server connection")), true);
        }
        try {
            String resultText = td.handler.handle(arguments);
            return new CallToolResult(List.of(new TextContent(String.valueOf(resultText))), false);
        } catch (Exception exc) {
            logger.error("Tool '{}' execution failed", name, exc);
            return new CallToolResult(List.of(new TextContent("Tool error: " + exc)), true);
        }
    }

    /** Generate tool declarations in OpenAI native function-calling format. */
    public List<Map<String, Object>> toOpenaiTools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : listTools()) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.get("name"));
            fn.put("description", t.get("description"));
            Object params = t.get("input_schema");
            if (params == null || ((Map<?, ?>) params).isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("type", "object");
                empty.put("properties", new LinkedHashMap<>());
                params = empty;
            }
            fn.put("parameters", params);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("type", "function");
            f.put("function", fn);
            out.add(f);
        }
        return out;
    }

    public List<String> toolNames() {
        return new ArrayList<>(tools.keySet());
    }

    public List<String> toolkitNames() {
        return new ArrayList<>(toolkits.keySet());
    }

    public int toolCount() {
        return tools.size();
    }

    public ToolDef getToolDef(String name) {
        return tools.get(name);
    }

    /** Build an MCP tool-call handler: handler(args) → server.callTool(toolName, args). */
    public static ToolHandler makeMcpHandler(MCPServer server, String toolName) {
        return args -> server.callTool(toolName, args);
    }
}
