package com.agent.software.tools;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * Toolkit base class (template: Note in toolkits/note is implemented this way).
 *
 * Each Toolkit corresponds to a business domain and aggregates multiple Tools via addTool().
 * trigger() dispatches to the handler of the matching Tool by tool name and returns its result
 * (returns null when not found).
 */
public abstract class Toolkit {

    protected final ArrayList<Tool> tools;

    public Toolkit(){
        this.tools = new ArrayList<>();
    }

    public Toolkit(ArrayList<Tool> tools){
        this.tools = tools;
    }

    protected void addTool(Tool tool){
        tools.add(tool);
    }

    public ArrayList<Tool> getTools(){
        return tools;
    }

    /**
     * Invokes by tool name. Returns the handler's result; returns null if the tool does not exist.
     */
    public String trigger(String toolName, Map<String, Object> arg){
        for(Tool tool : tools){
            if(tool.getToolName().equals(toolName)){
                return tool.handler(arg);
            }
        }
        return null;
    }

    /** Toolkit name (for the registry/logs). By default the class name converted to snake_case. */
    public String getName(){
        return snakeCase(getClass().getSimpleName());
    }

    /** Toolkit description (optional, for the registry). */
    public String getDescription(){
        return "Toolkit " + getName() + " (" + tools.size() + " tools)";
    }

    /** CamelCase → snake_case (TaskView → task_view, McpManager → mcp_manager). */
    public static String snakeCase(String name){
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < name.length(); i++){
            char c = name.charAt(i);
            if(Character.isUpperCase(c)){
                if(i > 0 && Character.isLowerCase(name.charAt(i - 1))){
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

}
