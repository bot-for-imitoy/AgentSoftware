package com.agent.software.llm;

import java.util.List;

/**
 * 一条 LLM 对话消息（system / user / assistant / tool）。
 *
 * <p>替代 master 中贯穿 {@code LLM}/{@code Conversation}/{@code OpenAICompatLLM} 的
 * {@code Map<String,Object>} 消息。
 */
public record Message(Role role, String content, List<ToolCallRequest> toolCalls, String toolCallId,
                      List<Double> embedding, boolean forgotten) {

    public Message {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        embedding = embedding == null ? List.of() : List.copyOf(embedding);
    }

    /** Backward-compatible constructor for transient request messages. */
    public Message(Role role, String content, List<ToolCallRequest> toolCalls, String toolCallId) {
        this(role, content, toolCalls, toolCallId, List.of(), false);
    }

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    /** 系统提示词：没有工具调用，也不关联 tool_call_id。 */
    public static Message system(String text) {
        return new Message(Role.SYSTEM, text, List.of(), null, List.of(), false);
    }

    /** 用户消息：没有工具调用，也不关联 tool_call_id。 */
    public static Message user(String text) {
        return new Message(Role.USER, text, List.of(), null, List.of(), false);
    }

    /** 助手回复；{@code toolCalls} 为 null 时归一为空列表，调用方无需判空。 */
    public static Message assistant(String text, List<ToolCallRequest> toolCalls) {
        return new Message(Role.ASSISTANT, text, toolCalls, null, List.of(), false);
    }

    /** 工具执行结果，通过 toolCallId 与请求关联。 */
    public static Message tool(String toolCallId, String content) {
        return new Message(Role.TOOL, content, List.of(), toolCallId, List.of(), false);
    }

    /** Adds persisted context metadata without changing the wire-level message fields. */
    public Message withContext(List<Double> vector, boolean isForgotten) {
        return new Message(role, content, toolCalls, toolCallId, vector, isForgotten);
    }
}
