package com.agent.software.agent.dialog;

import com.agent.software.agent.Agent;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.llm.LlmClient;
import com.agent.software.llm.Message;

import java.util.List;

/**
 * 角色 ↔ LLM 的当日对话记忆。
 *
 * <p>职责：跨任务连续性（历史 + 新任务）、超预算压缩、下班关闭当日上下文、随快照持久化。
 * 对应 master 的 {@code Conversation} + {@code ConversationManager}；但不再有进程级
 * 默认 manager，每个 {@link Agent} 各持一份。
 */
public final class ConversationMemory {

    public ConversationMemory(RoleId agent, ConversationPolicy policy) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 组装本次任务请求：[system prompt, ...历史, 当前任务]。 */
    public List<Message> prepare(String systemPrompt, String taskDescription, int day) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 任务完成后提交这一轮交换；超预算则触发压缩。 */
    public void commit(int day, String userText, String assistantText, LlmClient llm) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 下班关闭当日对话（上下文冲刷）。 */
    public void closeDay(int day) {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean isEmpty() {
        throw new UnsupportedOperationException("skeleton");
    }

    public int historySize() {
        throw new UnsupportedOperationException("skeleton");
    }

    public State snapshot() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void restore(State state) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 持久化形状。 */
    public record State(int day, int closedDay, List<Message> messages) {
    }
}
