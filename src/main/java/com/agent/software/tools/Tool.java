package com.agent.software.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool base class (template: WriteNote in toolkits/note is implemented this way).
 *
 * Each Tool corresponds to a function the LLM can call:
 *   - getToolName():    tool name (e.g. "write_note")
 *   - getSchema():      parameter descriptions. The template convention is a flat Map of parameter name -> description
 *                       (the value can also be a full property description Map).
 *   - getInputSchema(): converts the flat parameter descriptions into an OpenAI-style input_schema (for registration/LLM calls).
 *   - handler():        parameter validation + execution, returns the result text to the LLM.
 */
public abstract class Tool {

    public Tool(){
    }

    /** Tool name (the function name used when the LLM calls it). */
    public abstract String getToolName();

    /** Parameter descriptions: parameter name -> description (or a full property description Map). */
    public abstract Map<String, Object> getSchema();

    /**
     * Parameter input_schema (OpenAI style, the function signature exposed to the LLM):
     * {@code {type: object, properties: {...}}}.
     * When a flat schema value is a full property description Map it is passed through as-is
     * (types such as integer/boolean/enum can be declared).
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
                    props.put(e.getKey(), v);   // full property description, passed through as-is
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

    /** Executes the tool. args is the parameter dictionary, returns the result text. */
    public abstract String handler(Map<String, Object> args);

    /** Tool functional description (explanatory text for the LLM, optional). By default generated from the parameters. */
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
