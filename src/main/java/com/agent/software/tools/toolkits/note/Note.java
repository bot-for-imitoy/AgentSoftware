package com.agent.software.tools.toolkits.note;

import com.agent.software.role.Role;
import com.agent.software.store.NoteStore;
import com.agent.software.tools.Toolkit;

/**
 * 笔记工具包：write_note / read_note / edit_note / delete_note / list_notes。
 *
 * <p>笔记是角色自己的长期记忆（markdown 文件，跨天保留）。下班时上下文会被
 * {@code Context.forgetAll()} 移出 prompt，所以"明天还要记得的事"应当写进笔记。
 * 定时提醒请用 {@code task} 工具包（排期事件），不要写进笔记。
 */
public class Note extends Toolkit {

    public Note(NoteStore store) {
        addTool(new WriteNote(store));
        addTool(new ReadNote(store));
        addTool(new EditNote(store));
        addTool(new DeleteNote(store));
        addTool(new ListNotes(store));
    }

    public Note(Role role) {
        // 未绑定系统（如单测里临时造的 Role）时不给 store，避免在仓库 data/ 下乱建目录
        this(role == null || role.getSystem() == null ? null : role.noteStore());
    }

    @Override
    public String getDescription() {
        return "Notes: write_note / read_note / edit_note / delete_note / list_notes "
                + "(your own persistent markdown notes, kept across days)";
    }
}
