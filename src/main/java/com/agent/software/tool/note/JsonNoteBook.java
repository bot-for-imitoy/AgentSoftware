package com.agent.software.tool.note;

import com.agent.software.agent.dialog.DailySummary;
import com.agent.software.infra.config.AppPaths;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.Ids.RoleId;

import java.util.List;
import java.util.Optional;

/**
 * JSON 目录形态的笔记实现：每角色一份笔记文件，每日总结作为特殊笔记保存。
 *
 * <p>同时实现 {@link DailySummary}：提示词侧只认 agent 自己声明的那一个方法，
 * 不需要认识整个 {@link NoteBook}。
 */
public final class JsonNoteBook implements NoteBook, DailySummary {

    /** 绑定数据路径与 JSON 编解码器。 */
    public JsonNoteBook(AppPaths paths, JsonCodec json) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Note> list(RoleId owner) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<Note> read(RoleId owner, String title) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void write(Note note) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void edit(Note note) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public boolean delete(RoleId owner, String title) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void saveSummary(RoleId owner, int day, String body) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<String> summary(RoleId owner, int day) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<String> latestSummary(RoleId owner, int beforeDay) {
        throw new UnsupportedOperationException("skeleton");
    }
}
