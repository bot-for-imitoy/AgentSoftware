package com.agent.software.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 一次请求的结构化返回：正文 + 思维链 + 原生 tool_calls + token 用量。
 *
 * <p>独立成一个 Java 文件（你指定）。工具循环在 {@code Role}：拿到 Response 后若
 * {@link #hasToolCalls()} 就执行工具、回喂、再 {@code request()}。
 */
public final class Response {

    public final String text;
    public final String reasoning;
    public final List<Map<String, Object>> toolCalls;
    public final int tokens;

    public Response(String text, String reasoning, List<Map<String, Object>> toolCalls, int tokens) {
        this.text = text == null ? "" : text;
        this.reasoning = reasoning == null ? "" : reasoning;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.tokens = tokens;
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public int getTotalTokens() {
        return tokens;
    }

    @Override
    public String toString() {
        return "Response(tokens=" + tokens + ", toolCalls=" + toolCalls.size()
                + ", text=" + (text.length() > 60 ? text.substring(0, 60) + "…" : text) + ")";
    }
}
