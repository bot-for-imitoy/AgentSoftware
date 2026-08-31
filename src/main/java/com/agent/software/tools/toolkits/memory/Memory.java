package com.agent.software.tools.toolkits.memory;

import com.agent.software.role.AgentRole;
import com.agent.software.store.NoteStore;
import com.agent.software.tools.Toolkit;

/**
 * 记忆工具类 (Memory Toolkit) — 只包含记忆相关内容 (每日总结).
 *
 * 笔记类工具已分离到 toolkits.note (write_note / edit_note / list_notes /
 * read_note / delete_note), memory 不再包含任何笔记操作.
 */
public class Memory extends Toolkit {

    private final NoteStore noteStore;
    private final AgentRole agentRole;

    public Memory(NoteStore noteStore) {
        this.noteStore = noteStore;
        this.agentRole = null;
        addTool(new Summary(noteStore, agentRole));
    }

    public Memory(AgentRole agentRole) {
        this.noteStore = agentRole.noteStore();
        this.agentRole = agentRole;
        addTool(new Summary(noteStore, agentRole));
    }

    @Override
    public String getDescription(){
        return "记忆工具类: 总结今天的工作 (保存后下一天自动注入系统提示词)";
    }

}
