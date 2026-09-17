package com.agent.software.tool.note;

import com.agent.software.kernel.Ids.NoteId;
import com.agent.software.kernel.Ids.RoleId;

import java.time.Instant;

/** 一条笔记（或每日总结）。纯数据，不认识文件系统。 */
public record Note(NoteId id, RoleId owner, String title, String body,
                   Integer remindDay, Integer remindTick, Instant updatedAt) {

    /**
     * 每日总结的标题前缀约定。
     *
     * <p>总结与普通笔记共用同一张表、同一套读写接口，因此不做独立字段，
     * 而是把「前缀 + 天数」写进标题：{@code SUMMARY_TITLE_PREFIX + day}。
     * 这样 {@code summary(day)} / {@code latestSummary(beforeDay)} 可以复用
     * 按标题查找的路径，普通笔记也不会与总结撞名。
     */
    public static final String SUMMARY_TITLE_PREFIX = "__summary_day_";

    /** 是否为"每日总结"（标题约定）。 */
    public boolean isSummary() {
        return title != null && title.startsWith(SUMMARY_TITLE_PREFIX);
    }
}
