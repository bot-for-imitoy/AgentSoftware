package com.agent.software.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM 客户端接口 (Python 版 llm.py 公共接口, 与 MockLLM 同形):
 * <ul>
 *   <li>{@link #chat} → (responseText, tokensConsumed)</li>
 *   <li>{@link #summarize} → (summaryText, tokensConsumed)</li>
 *   <li>{@link #chatWithTools} → 原生 function calling</li>
 * </ul>
 */
public interface LLM {

    /** LLM 调用失败时的错误文本标记 (本包产生这些前缀, 消费方 roles 据此把任务标记为失败). */
    String LLM_ERROR_MARKERS = "[API error:";

    /** 聊天响应: 文本 + Token 数. */
    final class ChatResponse {
        public final String text;
        public final int tokens;

        public ChatResponse(String text, int tokens) {
            this.text = text;
            this.tokens = tokens;
        }
    }

    /** 原生 function calling 响应: content + 原始 tool_calls + usage. */
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

        /** 单任务累计 token (usage.total_tokens, 无 usage 时 0). */
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

    /** 发送聊天请求. */
    ChatResponse chat(String system, String user, double temperature, Integer maxTokens);

    /** 从日志/文本生成简洁总结. */
    ChatResponse summarize(String logText, double temperature, Integer maxTokens);

    /** 原生 function calling 请求 (OpenAI 兼容). */
    ToolsResponse chatWithTools(List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools,
                                double temperature, Integer maxTokens);
}
