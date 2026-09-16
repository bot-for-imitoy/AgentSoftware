package com.agent.software.kernel;

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
 */
public final class JsonSchema {

    private JsonSchema() {
    }

    /** 新建一个 object 根 schema。 */
    public static Builder object() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 链式构造器（每个方法返回自身以便串联）。 */
    public static final class Builder {

        public Builder string(String name, String description) {
            throw new UnsupportedOperationException("skeleton");
        }

        public Builder integer(String name, String description) {
            throw new UnsupportedOperationException("skeleton");
        }

        public Builder bool(String name, String description) {
            throw new UnsupportedOperationException("skeleton");
        }

        public Builder enumeration(String name, String description, List<String> values) {
            throw new UnsupportedOperationException("skeleton");
        }

        public Builder required(String... names) {
            throw new UnsupportedOperationException("skeleton");
        }

        /** 输出 OpenAI / MCP 兼容的 JSON Schema Map（仅在适配器边界使用）。 */
        public Map<String, Object> toMap() {
            throw new UnsupportedOperationException("skeleton");
        }
    }
}
