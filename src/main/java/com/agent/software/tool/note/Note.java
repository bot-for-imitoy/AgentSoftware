package com.agent.software.tool.note;

import com.agent.software.kernel.Ids.NoteId;
import com.agent.software.kernel.Ids.RoleId;

import java.time.Instant;

/** 一条笔记（或每日总结）。纯数据，不认识文件系统。 */
public record Note(NoteId id, RoleId owner, String title, String body,
                   Integer remindDay, Integer remindTick, Instant updatedAt) {

    /** 是否为"每日总结"（标题约定）。 */
    public boolean isSummary() {
        throw new UnsupportedOperationException("skeleton");
    }
}
