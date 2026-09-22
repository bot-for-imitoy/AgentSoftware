package com.agent.software.llm.context;

import com.agent.software.utils.DataRegistry;
import com.agent.software.utils.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 助手回复：可能带思维链和 tool_calls。 */
public final class AssistantMessage extends Message {

    public static final String DATA_TYPE = "assistant_message";

    static {
        DataRegistry.register(DATA_TYPE, () -> new AssistantMessage(""));
    }

    public List<Map<String, Object>> toolCalls = new ArrayList<>();
    public String reasoning = "";

    public AssistantMessage(String content) {
        super(content);
    }

    public AssistantMessage(String content, List<Map<String, Object>> toolCalls, String reasoning) {
        super(content);
        if (toolCalls != null) {
            this.toolCalls = new ArrayList<>(toolCalls);
        }
        this.reasoning = reasoning == null ? "" : reasoning;
    }

    public AssistantMessage(String uuid, String content, List<Map<String, Object>> toolCalls, String reasoning) {
        super(uuid, content);
        if (toolCalls != null) {
            this.toolCalls = new ArrayList<>(toolCalls);
        }
        this.reasoning = reasoning == null ? "" : reasoning;
    }

    @Override
    public String getRole() {
        return "assistant";
    }

    @Override
    public String type() {
        return DATA_TYPE;
    }

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = super.getData();
        d.put("tool_calls", Json.stringify(toolCalls == null ? List.of() : toolCalls));
        d.put("reasoning", reasoning == null ? "" : reasoning);
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        super.loadData(data);
        this.toolCalls = new ArrayList<>();
        if (data != null && data.get("tool_calls") != null && !data.get("tool_calls").isBlank()) {
            for (Object o : Json.parseArray(data.get("tool_calls"))) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> call = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        call.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    this.toolCalls.add(call);
                }
            }
        }
        this.reasoning = data == null ? "" : data.getOrDefault("reasoning", "");
    }
}
