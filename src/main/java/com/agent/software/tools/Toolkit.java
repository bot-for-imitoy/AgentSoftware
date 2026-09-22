package com.agent.software.tools;

import com.agent.software.utils.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一组工具的容器（不是 UUIDObject，也不需要 UUIDObjectManager —— Tool 没有身份）。
 */
public abstract class Toolkit {

    private final List<Tool> tools = new ArrayList<>();

    public Toolkit() {
    }

    public String getName() {
        return Text.snakeCase(getClass().getSimpleName());
    }

    public String getDescription() {
        return "Toolkit " + getName() + " (" + tools.size() + " tools)";
    }

    public int size() {
        return tools.size();
    }

    protected void addTool(Tool t) {
        if (t != null) {
            tools.add(t);
        }
    }

    public List<Tool> getTools() {
        return List.copyOf(tools);
    }

    /** 按名字触发工具；找不到返回 null。 */
    public String trigger(String toolName, Map<String, Object> args) {
        for (Tool t : tools) {
            if (t.getToolName().equals(toolName)) {
                return t.handler(args);
            }
        }
        return null;
    }
}
