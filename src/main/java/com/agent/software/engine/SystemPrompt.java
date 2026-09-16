package com.agent.software.engine;

import com.agent.software.model.RoleSpec;
import com.agent.software.ports.Clock;
import com.agent.software.ports.NoteBook;

/**
 * System Prompt 组装器。
 *
 * <p>从 master {@code AgentRole.buildSystemPrompt()}（约 55 行、混在角色对象里）抽出：
 * 只依赖只读的时钟与笔记，因此可单测，也不让 {@link Agent} 变胖。
 */
public final class SystemPrompt {

    private final Clock clock;
    private final NoteBook notes;

    public SystemPrompt(Clock clock, NoteBook notes) {
        this.clock = clock;
        this.notes = notes;
    }

    /** 组装该角色的完整 System Prompt（人设 / 时间 / 云盘与 Git 规则 / 昨日总结）。 */
    public String build(RoleSpec spec) {
        throw new UnsupportedOperationException("skeleton");
    }
}
