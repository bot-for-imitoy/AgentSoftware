package com.agent.software.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 类型化 JSON Schema 构造器。
 *
 * <p>目的：工具参数声明的唯一来源，替代 master {@code Tool.getSchema()} 的
 * {@code name → 描述字符串} 扁平 Map——那种写法无法表达 required / integer / enum，
 * 导致 handler 里到处做字符串转数字。
 *
 * <p>示例形态：{@code JsonSchema.object().string("title","笔记标题").required("title")}。
 *
 * <p>实现说明：{@code Builder} 继承 {@code JsonSchema}，因此链式调用的结果本身
 * 就是一个可直接放进 {@code ToolSpec} 的 schema 对象（无需额外的 {@code build()}）。
 */
public class JsonSchema {

    private final Map<String, Object> json;

    private JsonSchema(Map<String, Object> json) {
        this.json = json;
    }

    /** 输出 OpenAI / MCP 兼容的 JSON Schema Map（仅在适配器边界使用）。 */
    public Map<String, Object> toMap() {
        return json;
    }

    @Override
    public String toString() {
        return "JsonSchema" + json;
    }

    /** 新建一个 object 根 schema。 */
    public static Builder object() {
        return new Builder();
    }

    /** 链式构造器（每个方法返回自身以便串联）。 */
    public static final class Builder extends JsonSchema {

        private final Map<String, Map<String, Object>> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        private Builder() {
            super(new LinkedHashMap<>());
        }

        private Builder property(String name, Map<String, Object> definition) {
            if (name == null || name.isBlank()) {
                throw new DomainError("schema.name.blank", "参数名不能为空");
            }
            properties.put(name, definition);
            return this;
        }

        private static Map<String, Object> typed(String type, String description) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", type);
            if (description != null && !description.isBlank()) {
                def.put("description", description);
            }
            return def;
        }

        public Builder string(String name, String description) {
            return property(name, typed("string", description));
        }

        public Builder integer(String name, String description) {
            return property(name, typed("integer", description));
        }

        public Builder bool(String name, String description) {
            return property(name, typed("boolean", description));
        }

        public Builder enumeration(String name, String description, List<String> values) {
            Map<String, Object> def = typed("string", description);
            def.put("enum", values == null ? List.of() : List.copyOf(values));
            return property(name, def);
        }

        public Builder required(String... names) {
            if (names != null) {
                for (String n : names) {
                    if (n != null && !n.isBlank() && !required.contains(n)) {
                        required.add(n);
                    }
                }
            }
            return this;
        }

        /** 输出 OpenAI / MCP 兼容的 JSON Schema Map（仅在适配器边界使用）。 */
        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("type", "object");
            root.put("properties", new LinkedHashMap<>(properties));
            root.put("required", List.copyOf(required));
            return root;
        }
    }
}
