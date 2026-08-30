package com.maf.scheduler.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

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

    public boolean trigger(String toolName, Map<String, Object> arg){
        boolean find = false;
        for(Tool tool : tools){
            if(tool.getToolName().equals(toolName)){
                find = true;
                tool.handler(arg);
                break;
            }
            break;
        }
        return find;
    }

}
