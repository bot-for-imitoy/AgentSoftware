package com.agent.software.engine;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.Task;
import com.agent.software.policy.ToolLoopPolicy;
import com.agent.software.ports.LlmClient;
import com.agent.software.ports.Toolbox;
import com.agent.software.ports.Transcript;

/**
 * 一轮任务里的 LLM ↔ 工具循环（从 master {@code AgentRole.executeWithTools} 抽出）。
 *
 * <p>只负责循环本身：请求 → 执行工具 → 回填 tool 消息 → 直到不再有工具调用；
 * 上限与失败策略来自 {@link ToolLoopPolicy}，轨迹写 {@link Transcript}。
 */
public final class ToolLoop {

    private final LlmClient llm;
    private final Toolbox toolbox;
    private final Transcript transcript;
    private final ToolLoopPolicy policy;

    public ToolLoop(LlmClient llm, Toolbox toolbox, Transcript transcript, ToolLoopPolicy policy) {
        this.llm = llm;
        this.toolbox = toolbox;
        this.transcript = transcript;
        this.policy = policy;
    }

    public Outcome run(RoleId agent, String systemPrompt, Task task,
                       ConversationMemory memory, int day) {
        throw new UnsupportedOperationException("skeleton");
    }

    public record Outcome(String answer, int tokens, boolean failed) {
    }
}
