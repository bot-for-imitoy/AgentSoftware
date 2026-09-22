package com.agent.software.llm.context;

import com.agent.software.utils.DataRegistry;

import java.util.Map;

/** 工具执行结果回喂给模型的那条消息。 */
public final class ToolMessage extends Message {

    public static final String DATA_TYPE = "tool_message";

    static {
        DataRegistry.register(DATA_TYPE, () -> new ToolMessage("", "", ""));
    }

    public String toolCallId;
    public String name;

    public ToolMessage(String toolCallId, String name, String content) {
        super(content);
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.name = name == null ? "" : name;
    }

    public ToolMessage(String uuid, String toolCallId, String name, String content) {
        super(uuid, content);
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.name = name == null ? "" : name;
    }

    @Override
    public String getRole() {
        return "tool";
    }

    @Override
    public String type() {
        return DATA_TYPE;
    }

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = super.getData();
        d.put("tool_call_id", toolCallId == null ? "" : toolCallId);
        d.put("name", name == null ? "" : name);
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        super.loadData(data);
        this.toolCallId = data == null ? "" : data.getOrDefault("tool_call_id", "");
        this.name = data == null ? "" : data.getOrDefault("name", "");
    }
}
