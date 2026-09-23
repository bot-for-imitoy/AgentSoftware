package com.agent.software.tools.toolkits.memory;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * 记忆工具包：search_memory（语义检索自己的历史消息，含已移出 prompt 的那些）。
 *
 * <p>向量与淘汰由 {@code llm.context.SemanticMemory} 负责；这里只是把检索暴露给模型。
 * 没配 {@code embedding.model} 时工具会说"未启用"，而不是假装有记忆。
 */
public class Memory extends Toolkit {

    public Memory(Role role) {
        addTool(new SearchMemory(role));
    }

    @Override
    public String getDescription() {
        return "Memory: search_memory (semantic search over everything you have read, said or done before, "
                + "including messages dropped from the recent prompt)";
    }
}
