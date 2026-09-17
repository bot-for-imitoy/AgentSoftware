package com.agent.software.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link JsonSchema} 链式构造器输出 OpenAI/MCP 兼容形状。 */
class JsonSchemaTest {

    @SuppressWarnings("unchecked")
    @Test
    void 对象schema的形状() {
        JsonSchema schema = JsonSchema.object()
                .string("title", "标题")
                .integer("count", "数量")
                .bool("urgent", "是否紧急")
                .enumeration("status", "状态", List.of("PENDING", "DONE"))
                .required("title", "status");

        Map<String, Object> map = schema.toMap();
        assertEquals("object", map.get("type"));
        assertEquals(List.of("title", "status"), map.get("required"));

        Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) map.get("properties");
        assertEquals("string", props.get("title").get("type"));
        assertEquals("标题", props.get("title").get("description"));
        assertEquals("integer", props.get("count").get("type"));
        assertEquals("boolean", props.get("urgent").get("type"));
        assertEquals(List.of("PENDING", "DONE"), props.get("status").get("enum"));
    }

    @Test
    void 链式调用返回的就是schema本身() {
        // 骨架里 ToolSpec(name, description, JsonSchema) 要一个 JsonSchema，
        // 而 object() 返回 Builder —— 两者必须是同一个对象，否则没法直接用。
        assertInstanceOf(JsonSchema.class, JsonSchema.object().string("a", "b"));
        assertTrue(JsonSchema.object().toMap().containsKey("properties"));
    }

    @Test
    void 重复required不重复计入() {
        JsonSchema schema = JsonSchema.object().string("a", "").required("a", "a");
        assertEquals(List.of("a"), schema.toMap().get("required"));
    }
}
