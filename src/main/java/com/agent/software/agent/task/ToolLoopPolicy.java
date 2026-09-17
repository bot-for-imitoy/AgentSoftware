package com.agent.software.agent.task;

/**
 * 工具循环的上限与失败策略。
 *
 * <p>master 对应 {@code AgentRole.MAX_TOOL_ROUNDS} / {@code MAX_TOOL_TOTAL_TOKENS}
 * （后者当时被注释掉，导致循环没有真正的 token 预算）。
 */
public record ToolLoopPolicy(int maxRounds, int maxTotalTokens, boolean failOnLlmError) {

    public static ToolLoopPolicy defaults() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean roundBudgetExceeded(int round) {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean tokenBudgetExceeded(int tokens) {
        throw new UnsupportedOperationException("skeleton");
    }
}
