package com.agent.software.ports;

import java.util.List;

/**
 * One message in an LLM conversation.
 *
 * @param role       {@code system}, {@code user}, {@code assistant} or {@code tool}
 * @param content    text content (may be empty)
 * @param toolCalls  assistant tool calls (empty for other roles)
 * @param toolCallId id a {@code role=tool} result answers (empty otherwise)
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

    public ChatMessage {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("ChatMessage role must not be blank");
        }
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolCallId = toolCallId == null ? "" : toolCallId;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of(), "");
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of(), "");
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, toolCalls, "");
    }

    public static ChatMessage toolResult(String toolCallId, String content) {
        return new ChatMessage("tool", content, List.of(), toolCallId);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
