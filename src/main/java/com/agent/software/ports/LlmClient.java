package com.agent.software.ports;

import com.agent.software.model.Message;
import com.agent.software.model.ToolSpec;

import java.util.List;

/**
 * LLM 能力。
 *
 * <p>唯一实现是 {@code adapters.llm.OpenAiClient}。失败通过 {@link ChatReply#failed()}
 * 或 {@code kernel.DomainError} 表达，不再用 {@code "[API error:"} 前缀。
 */
public interface LlmClient {

    ChatReply chat(ChatRequest request);

    ToolReply chatWithTools(ToolChatRequest request);

    ChatReply summarize(String text, double temperature, int maxTokens);

    record ChatRequest(String system, String user, double temperature, Integer maxTokens) {
    }

    record ChatReply(String text, String reasoning, int tokens) {

        public boolean failed() {
            throw new UnsupportedOperationException("skeleton");
        }
    }

    record ToolChatRequest(List<Message> messages, List<ToolSpec> tools,
                           double temperature, Integer maxTokens) {
    }

    record ToolReply(String content, String reasoning,
                     List<com.agent.software.model.ToolCallRequest> toolCalls, int totalTokens) {
    }
}
