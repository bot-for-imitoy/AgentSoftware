package com.agent.software.tools.toolkits.memory;

import com.agent.software.role.AgentRole;
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
        return "Memory toolkit: summarize today's work (after saving, it is automatically injected into the system prompt the next day)";
    }

}
