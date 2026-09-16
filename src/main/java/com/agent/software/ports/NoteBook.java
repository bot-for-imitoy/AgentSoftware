package com.agent.software.ports;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.Note;

import java.util.List;
import java.util.Optional;

/**
 * 笔记与每日总结的读写能力。
 *
 * <p>是"域端口"，不是文件系统：实现可以是 JSON/Markdown 目录，也可以是内存 fake。
 */
public interface NoteBook {

    List<Note> list(RoleId owner);

    Optional<Note> read(RoleId owner, String title);

    void write(Note note);

    void edit(Note note);

    boolean delete(RoleId owner, String title);

    /** 保存第 day 天的总结（总结是特殊的笔记）。 */
    void saveSummary(RoleId owner, int day, String body);

    Optional<String> summary(RoleId owner, int day);

    /** 取严格早于 beforeDay 的最近一份总结（用于次日冷启动提示词）。 */
    Optional<String> latestSummary(RoleId owner, int beforeDay);
}
