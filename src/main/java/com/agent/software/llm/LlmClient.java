package com.agent.software.llm;

import com.agent.software.tool.spi.ToolSpec;

import java.util.List;

/**
 * LLM 能力。
 *
 * <p>唯一实现是 {@code llm.OpenAiClient}。失败通过 {@link ChatReply#failed()}
 * 或 {@code kernel.DomainError} 表达，不再用 {@code "[API error:"} 前缀。
 */
public interface LlmClient {

    ChatReply chat(ChatRequest request);

    ToolReply chatWithTools(ToolChatRequest request);

    ChatReply summarize(String text, double temperature, int maxTokens);

    record ChatRequest(String system, String user, double temperature, Integer maxTokens) {
    }

    record ChatReply(String text, String reasoning, int tokens) {

        /** 失败判定：没有可用文本即失败（不再嗅探 {@code "[API error:"} 前缀）。 */
        public boolean failed() {
            return text == null || text.isBlank();
        }
    }

    record ToolChatRequest(List<Message> messages, List<ToolSpec> tools,
                           double temperature, Integer maxTokens) {
    }

    record ToolReply(String content, String reasoning,
                     List<com.agent.software.llm.ToolCallRequest> toolCalls, int totalTokens) {
    }
}
