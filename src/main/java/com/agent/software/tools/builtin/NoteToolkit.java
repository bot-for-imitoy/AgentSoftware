package com.agent.software.tools.builtin;

import com.agent.software.ports.NoteBook;
import com.agent.software.ports.ReminderScheduler;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.ToolContext;
import com.agent.software.tools.spi.Toolkit;

import java.util.List;

/**
 * 笔记工具包（id {@code "note"}），暴露工具：write_note / edit_note / list_notes / read_note / delete_note。
 */
public final class NoteToolkit implements Toolkit {

    private final NoteBook notes;
    private final ReminderScheduler reminders;

    public NoteToolkit(NoteBook notes, ReminderScheduler reminders) {
        this.notes = notes;
        this.reminders = reminders;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate(ToolContext context) {
        throw new UnsupportedOperationException("skeleton");
    }
}
