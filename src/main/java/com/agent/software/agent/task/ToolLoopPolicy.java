package com.agent.software.agent.task;

/**
 * 工具循环的上限与失败策略。
 *
 * <p>master 对应 {@code AgentRole.MAX_TOOL_ROUNDS} / {@code MAX_TOOL_TOTAL_TOKENS}
 * （后者当时被注释掉，导致循环没有真正的 token 预算）。
 */
public record ToolLoopPolicy(int maxRounds, int maxTotalTokens, boolean failOnLlmError) {

    /** master {@code AgentRole} 的默认上限：12 轮工具调用、60000 token 总预算。 */
    public static ToolLoopPolicy defaults() {
        return new ToolLoopPolicy(12, 60_000, false);
    }

    public boolean roundBudgetExceeded(int round) {
        return maxRounds > 0 && round > maxRounds;
    }

    public boolean tokenBudgetExceeded(int tokens) {
        return maxTotalTokens > 0 && tokens > maxTotalTokens;
    }
}
