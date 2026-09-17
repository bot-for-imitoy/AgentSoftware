package com.agent.software.agent.dialog;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.sim.clock.Clock;

/**
 * System Prompt 组装器。
 *
 * <p>从 master {@code AgentRole.buildSystemPrompt()}（约 55 行、混在角色对象里）抽出：
 * 只依赖只读的时钟与一份 {@link DailySummary}，因此可单测，也不让
 * {@code agent.Agent} 继续变胖。
 *
 * <p>注意这里不依赖 {@code tool.note.NoteBook}：agent 包只认自己声明的窄端口，
 * 不认任何具体工具包（实现在 tool 侧，见 PLAN §3 依赖规则）。
 */
public final class SystemPrompt {

    private final Clock clock;
    private final DailySummary summaries;

    public SystemPrompt(Clock clock, DailySummary summaries) {
        this.clock = clock;
        this.summaries = summaries;
    }

    /** 组装该角色的完整 System Prompt（人设 / 时间 / 云盘与 Git 规则 / 昨日总结）。 */
    public String build(RoleSpec spec) {
        throw new UnsupportedOperationException("skeleton");
    }
}
