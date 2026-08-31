package com.maf.scheduler.tools;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * 工具类基类 (模板: toolkits/note 中的 Note 即按此实现).
 *
 * 每个 Toolkit 对应一个业务域, 通过 addTool() 聚合多个 Tool.
 * trigger() 按工具名分发到对应 Tool 的 handler, 返回其执行结果
 * (未找到时返回 null).
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
     * 按工具名调用. 返回 handler 的执行结果; 工具不存在返回 null.
     */
    public String trigger(String toolName, Map<String, Object> arg){
        for(Tool tool : tools){
            if(tool.getToolName().equals(toolName)){
                return tool.handler(arg);
            }
        }
        return null;
    }

    /** 工具类名 (供注册表/日志使用). 默认取类名转 snake_case. */
    public String getName(){
        return snakeCase(getClass().getSimpleName());
    }

    /** 工具类描述 (可选, 供注册表使用). */
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
