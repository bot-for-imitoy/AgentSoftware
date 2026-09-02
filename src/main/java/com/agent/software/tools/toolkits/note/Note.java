package com.agent.software.tools.toolkits.note;

import com.agent.software.role.AgentRole;
import com.agent.software.store.NoteStore;
import com.agent.software.tools.Toolkit;

/**
 * Note toolkit class (Note Toolkit) - separated from memory, contains only note-related tools:
 * write_note / edit_note / list_notes / read_note / delete_note.
 *
 * Memory keeps only memory-related content such as the daily summary (see toolkits.memory).
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
        return "Note toolkit: write/edit/list/read/delete notes (supports Tick reminders; notes and scheduled tasks are unified)";
    }

}
