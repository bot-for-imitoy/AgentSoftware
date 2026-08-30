package com.maf.scheduler.tools;

import com.maf.scheduler.core.ToolRegistry.ToolKit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 新旧工具体系桥接: 把模板风格 (toolkits.*, Tool/Toolkit) 的工具类
 * 转换为旧版 ToolRegistry.ToolKit, 使其可被 AgentRole 加载并暴露给 LLM.
 *
 * 模板的 getSchema() 约定: 参数名 → 说明文字 (扁平 Map);
 * 桥接时生成 OpenAI 风格的 input_schema (type=object + properties).
 * 若某参数的值是完整属性描述 Map, 则原样透传 (可用于声明 integer/boolean/enum 等类型).
 */
public final class ToolkitBridge {

    private ToolkitBridge() {
    }

    /** 新风格 Toolkit → 旧版 ToolKit (供 AgentRole.addToolkit / ToolRegistry 使用). */
    public static ToolKit toLegacy(Toolkit toolkit) {
        ToolKit tk = new ToolKit(toolkit.getName(), toolkit.getDescription());
        for (Tool tool : toolkit.getTools()) {
            tk.addPythonTool(tool.getToolName(), tool.getDescription(),
                    toInputSchema(tool.getSchema()), tool::handler);
        }
        return tk;
    }

    /** 模板扁平 schema (参数名 → 说明/属性 Map) → OpenAI input_schema. */
    public static Map<String, Object> toInputSchema(Map<String, Object> schema) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        if (schema != null) {
            for (Map.Entry<String, Object> e : schema.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Map<?, ?>) {
                    props.put(e.getKey(), v);   // 完整属性描述, 原样透传
                } else {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("type", "string");
                    p.put("description", String.valueOf(v));
                    props.put(e.getKey(), p);
                }
            }
        }
        input.put("properties", props);
        return input;
    }

}
