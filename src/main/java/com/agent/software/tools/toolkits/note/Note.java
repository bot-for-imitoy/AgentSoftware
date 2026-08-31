package com.agent.software.tools.toolkits.note;

import com.agent.software.role.AgentRole;
import com.agent.software.store.NoteStore;
import com.agent.software.tools.Toolkit;

/**
 * 笔记工具类 (Note Toolkit) — 与记忆分离, 只包含笔记相关工具:
 * write_note / edit_note / list_notes / read_note / delete_note.
 *
 * 记忆 (memory) 只保留每日总结等记忆相关内容 (见 toolkits.memory).
 */
public class Note extends Toolkit {

    private final NoteStore noteStore;

    public Note(NoteStore noteStore){
        this.noteStore = noteStore;
        addTool(new WriteNote(noteStore));
        addTool(new EditNote(noteStore));
        addTool(new ListNotes(noteStore));
        addTool(new ReadNote(noteStore));
        addTool(new DeleteNote(noteStore));
    }

    public Note(AgentRole agentRole) {
        this(agentRole.noteStore());
    }

    @Override
    public String getDescription(){
        return "笔记工具类: 写/编辑/列出/读取/删除笔记 (可带 Tick 提醒, 笔记与定时任务统一)";
    }

}
