package com.agent.software.llm;

import com.agent.software.llm.context.AssistantMessage;
import com.agent.software.llm.context.Context;
import com.agent.software.llm.context.Message;
import com.agent.software.llm.context.ToolMessage;
import com.agent.software.llm.context.UserMessage;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 抽象：状态化会话（append* 写上下文，{@link #request()} 发一次请求）。
 *
 * <p>工具循环不在这里 —— 在 {@code Role}。LLM 只需要知道"要声明哪些工具"，
 * 所以有 {@link #setTools(List)}（这是在 v3 冻结 API 之外补的一个方法，见报备清单）。
 */
public abstract class LLM implements Data {

    private Context context;
    private String systemPrompt = "";
    private double temperature = 0.7;
    private Integer maxTokens;
    private List<Tool> tools = new ArrayList<>();
    private int totalTokens = 0;

    public LLM() {
    }

    // ── 配置 ────────────────────────────────────────────────────

    public abstract String getModel();

    public abstract String getEndpoint();

    public void setTemperature(double t) {
        this.temperature = t;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setMaxTokens(Integer n) {
        this.maxTokens = n;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    // ── 上下文 ──────────────────────────────────────────────────

    public Context getContext() {
        return context;
    }

    public void setContext(Context c) {
        this.context = c;
    }

    public void setSystemPrompt(String p) {
        this.systemPrompt = p == null ? "" : p;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    /** 声明本次会话可用的工具（function calling 的 tools 字段）。 */
    public void setTools(List<Tool> tools) {
        this.tools = tools == null ? new ArrayList<>() : new ArrayList<>(tools);
    }

    /** 供子类读取工具列表（protected，不对外）。 */
    protected List<Tool> toolList() {
        return tools;
    }

    // ── 追加消息 ────────────────────────────────────────────────

    public void appendMessage(Message m) {
        requireContext().add(m);
    }

    public void appendUserMessage(String text) {
        appendMessage(new UserMessage(text));
    }

    public void appendAssistantMessage(String text) {
        appendMessage(new AssistantMessage(text));
    }

    public void appendAssistantMessage(String text, List<Map<String, Object>> toolCalls) {
        appendMessage(new AssistantMessage(text, toolCalls, ""));
    }

    public void appendToolResult(String toolCallId, String name, String result) {
        appendMessage(new ToolMessage(toolCallId, name, result));
    }

    // ── 请求 ────────────────────────────────────────────────────

    /** 统一入口：发一次请求并返回结构化结果。 */
    public abstract Response request();

    public int getTotalTokens() {
        return totalTokens;
    }

    public void resetTokens() {
        this.totalTokens = 0;
    }

    /** 子类每次请求成功后累加。 */
    protected void addTokens(int tokens) {
        if (tokens > 0) {
            this.totalTokens += tokens;
        }
    }

    public void close() {
    }

    // ── 持久化 ──────────────────────────────────────────────────

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("system_prompt", systemPrompt);
        d.put("temperature", Double.toString(temperature));
        d.put("max_tokens", maxTokens == null ? "" : Integer.toString(maxTokens));
        d.put("total_tokens", Integer.toString(totalTokens));
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        if (data == null) {
            return;
        }
        this.systemPrompt = data.getOrDefault("system_prompt", "");
        try {
            this.temperature = Double.parseDouble(data.getOrDefault("temperature", "0.7"));
        } catch (NumberFormatException ignored) {
        }
        String mt = data.get("max_tokens");
        this.maxTokens = mt == null || mt.isBlank() ? null : Integer.valueOf(mt.trim());
        try {
            this.totalTokens = Integer.parseInt(data.getOrDefault("total_tokens", "0"));
        } catch (NumberFormatException ignored) {
        }
    }

    private Context requireContext() {
        if (context == null) {
            throw new IllegalStateException("LLM context not set");
        }
        return context;
    }
}
