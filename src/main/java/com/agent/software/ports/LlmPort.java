package com.agent.software.ports;

import java.util.List;

/**
 * Language-model port.
 *
 * <p>A single request object replaces the historical positional
 * {@code (system, user, temperature, maxTokens)} signatures. Implementations
 * report failures as text prefixed with {@link #API_ERROR_PREFIX} rather than
 * throwing, so the tool loop can mark the task failed.
 */
public interface LlmPort {

    /** Prefix every adapter uses for a failed call. */
    String API_ERROR_PREFIX = "[API error:";

    ChatReply chat(ChatRequest request);

    ChatReply summarize(String text, int maxTokens);

    ToolReply chatWithTools(ToolRequest request);

    record ChatRequest(String system, String user, double temperature, int maxTokens) {
        public ChatRequest {
            system = system == null ? "" : system;
            user = user == null ? "" : user;
        }

        public ChatRequest(String system, String user) {
            this(system, user, 0.7, 512);
        }
    }

    record ChatReply(String text, String reasoning, int tokens) {
        public ChatReply {
            text = text == null ? "" : text;
            reasoning = reasoning == null ? "" : reasoning;
        }

        public boolean failed() {
            return text.startsWith(API_ERROR_PREFIX);
        }
    }

    record ToolRequest(List<ChatMessage> messages, List<ToolSpec> tools, double temperature, Integer maxTokens) {
        public ToolRequest {
            messages = messages == null ? List.of() : List.copyOf(messages);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    record ToolReply(String content, String reasoning, List<ToolCall> toolCalls, int tokens) {
        public ToolReply {
            content = content == null ? "" : content;
            reasoning = reasoning == null ? "" : reasoning;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public boolean failed() {
            return content.startsWith(API_ERROR_PREFIX);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }
}
