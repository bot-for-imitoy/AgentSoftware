package com.agent.software.tools.toolkits.memory;

import com.agent.software.role.Role;
import com.agent.software.store.NoteStore;
import com.agent.software.tools.Toolkit;

/**
 * Memory toolkit class (Memory Toolkit) - contains only memory-related content (daily summary).
 *
 * Note tools have been split out into toolkits.note (write_note / edit_note / list_notes /
 * read_note / delete_note); memory no longer contains any note operations.
 */
public class Memory extends Toolkit {

    private final NoteStore noteStore;
    private final Role role;

    public Memory(NoteStore noteStore) {
        this.noteStore = noteStore;
        this.role = null;
        addTool(new Summary(noteStore, role));
    }

    public Memory(Role role) {
        this.noteStore = role.noteStore();
        this.role = role;
        addTool(new Summary(noteStore, role));
    }

    @Override
    public String getDescription(){
        return "Memory toolkit: summarize today's work (after saving, it is automatically injected into the system prompt the next day)";
    }

}
