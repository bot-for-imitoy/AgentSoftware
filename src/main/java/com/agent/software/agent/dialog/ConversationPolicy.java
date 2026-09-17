package com.agent.software.agent.dialog;

/**
 * 对话历史的压缩策略。
 *
 * <p>master 把这些魔数写在 {@code Conversation} 里（24000 字符预算 / 12000 摘要上限 /
 * 6 条工具回执）。抽出来后与状态分离，可单测、可配置。
 */
public record ConversationPolicy(int maxHistoryChars, int maxSummaryChars, int toolRecapLimit) {

    /** master {@code Conversation} 的默认值。 */
    public static ConversationPolicy defaults() {
        return new ConversationPolicy(24_000, 12_000, 6);
    }

    public boolean shouldCompact(long totalChars) {
        return maxHistoryChars > 0 && totalChars > maxHistoryChars;
    }

    /** 压缩失败时至少保留的最近消息条数。 */
    public int keepMessages() {
        return toolRecapLimit <= 0 ? 1 : Math.max(2, toolRecapLimit);
    }
}
