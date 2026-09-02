package com.agent.software.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM client interface (the public interface of the Python llm.py, same shape as MockLLM):
 * <ul>
 *   <li>{@link #chat} → (responseText, tokensConsumed)</li>
 *   <li>{@link #summarize} → (summaryText, tokensConsumed)</li>
 *   <li>{@link #chatWithTools} → native function calling</li>
 * </ul>
 */
public interface LLM {

    /** Error text marker when an LLM call fails (this package produces these prefixes, and consumer roles use them to mark tasks as failed). */
    String LLM_ERROR_MARKERS = "[API error:";

    /** Chat response: text + token count. */
    final class ChatResponse {
        public final String text;
        public final int tokens;

        public ChatResponse(String text, int tokens) {
            this.text = text;
            this.tokens = tokens;
        }
    }

    /** Native function calling response: content + raw tool_calls + usage. */
    final class ToolsResponse {
        public final String content;
        public final List<Map<String, Object>> toolCalls;
        public final Map<String, Object> usage;

        public ToolsResponse(String content, List<Map<String, Object>> toolCalls,
                             Map<String, Object> usage) {
            this.content = content;
            this.toolCalls = toolCalls != null ? toolCalls : List.of();
            this.usage = usage;
        }

        /** Total tokens for a single task (usage.total_tokens, 0 when usage is absent). */
        public int totalTokens() {
            if (usage == null) {
                return 0;
            }
            Object t = usage.get("total_tokens");
            if (t instanceof Number n) {
                return n.intValue();
            }
            return 0;
        }
    }

    /** Send a chat request. */
    ChatResponse chat(String system, String user, double temperature, Integer maxTokens);

    /** Generate a concise summary from logs/text. */
    ChatResponse summarize(String logText, double temperature, Integer maxTokens);

    /** Native function calling request (OpenAI compatible). */
    ToolsResponse chatWithTools(List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools,
                                double temperature, Integer maxTokens);
}
