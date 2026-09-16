package com.agent.software.adapters.persistence;

import com.agent.software.adapters.config.AppPaths;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.Note;
import com.agent.software.ports.JsonCodec;
import com.agent.software.ports.NoteBook;

import java.util.List;
import java.util.Optional;

/**
 * JSON 目录形态的笔记实现：每角色一份笔记文件，每日总结作为特殊笔记保存。
 */
public final class JsonNoteBook implements NoteBook {

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
