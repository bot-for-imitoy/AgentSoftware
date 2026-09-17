package com.agent.software.llm;

import java.util.List;

/**
 * 一条 LLM 对话消息（system / user / assistant / tool）。
 *
 * <p>替代 master 中贯穿 {@code LLM}/{@code Conversation}/{@code OpenAICompatLLM} 的
 * {@code Map<String,Object>} 消息。
 */
public record Message(Role role, String content, List<ToolCallRequest> toolCalls, String toolCallId) {

    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT,
        TOOL
    }

    public static Message system(String text) {
        throw new UnsupportedOperationException("skeleton");
    }

    public static Message user(String text) {
        throw new UnsupportedOperationException("skeleton");
    }

    public static Message assistant(String text, List<ToolCallRequest> toolCalls) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 工具执行结果，通过 toolCallId 与请求关联。 */
    public static Message tool(String toolCallId, String content) {
        throw new UnsupportedOperationException("skeleton");
    }
}
