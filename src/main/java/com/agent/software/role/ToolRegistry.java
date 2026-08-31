package com.agent.software.role;

import com.agent.software.core.MCPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP + Python Tool System (Python 版 tools.py 的 Java 对应物).
 *
 * 两层架构:
 *   ToolKit      — 一组相关工具的命名集合 (MCP 或 Python 原生).
 *   ToolRegistry — 每角色工具注册表, 管理 ToolKit.
 */
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    /** 工具 handler: 接收参数字典, 返回字符串结果 (Python ToolHandler 语义). */
    @FunctionalInterface
    public interface ToolHandler {
        String handle(Map<String, Object> args);
    }

    /** 统一工具定义 — MCP 与 Python 原生工具共用. */
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

    /** MCP 工具结果文本内容 (CallToolResult 的组成). */
    public static final class TextContent {
        public String type = "text";
        public String text = "";

        public TextContent(String text) {
            this.text = text;
        }
    }

    /** MCP 工具调用结果. */
    public static final class CallToolResult {
        public List<TextContent> content = new ArrayList<>();
        public boolean isError = false;

        public CallToolResult(List<TextContent> content, boolean isError) {
            this.content = content;
            this.isError = isError;
        }
    }

    /** 一组相关工具 (Python 原生 / MCP / 混合). */
    public static final class ToolKit {
        public String name;
        public String description;
        private final Map<String, ToolDef> tools = new LinkedHashMap<>();
        /** 工具类上下文绑定 (role / manager / store 等). */
        private final Map<String, Object> bindings = new LinkedHashMap<>();

        public ToolKit(String name, String description) {
            this.name = name;
            this.description = description != null ? description : "";
        }

        // ── 上下文绑定 (bind/require) ─────────────────────────

        public void bind(String key, Object value) {
            bindings.put(key, value);
        }

        public Object get(String key, Object def) {
            return bindings.getOrDefault(key, def);
        }

        /** 读取绑定上下文, 未绑定时抛清晰错误. */
        public Object require(String key, String what) {
            Object v = bindings.get(key);
            if (v == null) {
                throw new RuntimeException(what + "尚未绑定, 请通过 role.addToolkit() 注册该工具类");
            }
            return v;
        }

        // ── Python tool management ────────────────────────────

        /** 添加 Python 原生工具. */
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

        /** 直接添加已构建的 ToolDef (source 由 td 自带, 供 MCP 工具等使用). */
        public void addTool(ToolDef td) {
            if (tools.containsKey(td.name)) {
                throw new IllegalArgumentException("Tool '" + td.name + "' already exists in toolkit '" + this.name + "'");
            }
            tools.put(td.name, td);
            logger.info("ToolKit[{}] tool added: {} (source={})", this.name, td.name, td.source);
        }

        /** 删除工具. 返回是否存在. */
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

    /** 导入整个工具类. 返回新增工具数 (重复跳过). */
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

    /** 移除一个工具类及其全部工具. 返回移除的工具数. */
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

    /** 注册单个工具. */
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

    /** 返回全部工具 (LLM 兼容格式). */
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

    /** 执行工具. 搜索所有已加载的工具类. */
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

    /** 生成 OpenAI 原生 function calling 格式的工具声明列表. */
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

    /** 构造 MCP 工具调用 handler: handler(args) → server.callTool(toolName, args). */
    public static ToolHandler makeMcpHandler(MCPServer server, String toolName) {
        return args -> server.callTool(toolName, args);
    }
}
