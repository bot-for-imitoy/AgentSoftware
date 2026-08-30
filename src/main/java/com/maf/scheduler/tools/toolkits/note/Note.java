package com.maf.scheduler.tools.toolkits.note;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.NoteStore;
import com.maf.scheduler.tools.Toolkit;

public class Note extends Toolkit {

    private final NoteStore noteStore;

    public Note(NoteStore noteStore){
        this.noteStore = noteStore;
    }

    public Note(AgentRole agentRole) {
        this(agentRole.noteStore());
        addTool(new WriteNote(noteStore));
    }

}
