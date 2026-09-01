package com.agent.software.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具基类 (模板: toolkits/note 中的 WriteNote 即按此实现).
 *
 * 每个 Tool 对应一个 LLM 可调用的函数:
 *   - getToolName():    工具名 (如 "write_note")
 *   - getSchema():      参数说明. 模板约定为 参数名 → 说明文字 的扁平 Map
 *                       (值也可以是完整属性描述 Map).
 *   - getInputSchema(): 把扁平参数说明转为 OpenAI 风格 input_schema (供注册/LLM 调用).
 *   - handler():        参数校验 + 执行, 返回给 LLM 的结果文本.
 */
public abstract class Tool {

    public Tool(){
    }

    /** 工具名 (LLM 调用时的函数名). */
    public abstract String getToolName();

    /** 参数说明: 参数名 → 说明 (或完整属性描述 Map). */
    public abstract Map<String, Object> getSchema();

    /**
     * 参数 input_schema (OpenAI 风格, 暴露给 LLM 的 function 签名):
     * {@code {type: object, properties: {...}}}.
     * 扁平 schema 中值为完整属性描述 Map 时原样透传
     * (可声明 integer/boolean/enum 等类型).
     */
    public Map<String, Object> getInputSchema(){
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> schema = getSchema();
        if(schema != null){
            for(Map.Entry<String, Object> e : schema.entrySet()){
                Object v = e.getValue();
                if(v instanceof Map<?, ?>){
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

    /** 执行工具. args 为参数字典, 返回结果文本. */
    public abstract String handler(Map<String, Object> args);

    /** 工具功能描述 (给 LLM 看的说明文字, 可选). 默认按参数生成. */
    public String getDescription(){
        Map<String, Object> schema = getSchema();
        if(schema == null || schema.isEmpty()){
            return "Tool " + getToolName();
        }
        StringBuilder sb = new StringBuilder("Tool ").append(getToolName()).append(": params ");
        boolean first = true;
        for(Map.Entry<String, Object> e : schema.entrySet()){
            if(!first){
                sb.append(", ");
            }
            first = false;
            Object v = e.getValue();
            String desc = v instanceof Map<?, ?> m ? String.valueOf(m.get("description")) : String.valueOf(v);
            sb.append(e.getKey()).append('(').append(desc == null || desc.isEmpty() ? "" : desc).append(')');
        }
        return sb.toString();
    }

}
