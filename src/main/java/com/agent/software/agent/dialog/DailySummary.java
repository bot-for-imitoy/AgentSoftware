package com.agent.software.agent.dialog;

import com.agent.software.kernel.Ids.RoleId;

import java.util.Optional;

/**
 * 提示词需要的一小块“昨天”数据。
 *
 * <p>为什么不让 {@link SystemPrompt} 直接用 {@code tool.note.NoteBook}：
 * 那样 {@code agent.dialog → tool.note}，而 {@code tool.note.MemoryToolkit}
 * 又要拿 {@code agent.AgentControl}，两个 feature 就互相依赖了。
 * 这里按“需要什么声明什么”开一个窄口子，实现方仍是
 * {@code tool.note.JsonNoteBook}（跨包 implements，属于正常的接缝）。
 */
@FunctionalInterface
public interface DailySummary {

    /** 该角色 {@code beforeDay} 之前最近一份总结；没有就返回空。 */
    Optional<String> latestSummary(RoleId owner, int beforeDay);
}
