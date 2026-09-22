package com.agent.software.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支持 OpenAI function calling 的工具：在 {@link Tool} 之上提供 API 需要的声明格式。
 */
public class OpenAITool extends Tool {

    @Override
    public String getToolName() {
        throw new UnsupportedOperationException("OpenAITool subclasses must implement getToolName()");
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        throw new UnsupportedOperationException("OpenAITool subclasses must implement handler()");
    }

    /** {type:function, function:{name, description, parameters}} */
    public Map<String, Object> toSpec() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", getToolName());
        function.put("description", getDescription());
        function.put("parameters", getInputSchema());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", "function");
        spec.put("function", function);
        return spec;
    }
}
