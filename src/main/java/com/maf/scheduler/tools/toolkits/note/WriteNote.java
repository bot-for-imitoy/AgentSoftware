package com.maf.scheduler.tools.toolkits.note;

import com.maf.scheduler.store.NoteStore;
import com.maf.scheduler.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

public class WriteNote extends Tool {

    private final NoteStore noteStore;

    public WriteNote(NoteStore noteStore) {
        super();
        this.noteStore = noteStore;
    }

    @Override
    public String getToolName() {
        return "write_note";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "The name of this note.");
        schema.put("content", "The content of your note.");
        schema.put("reminder_tick", "(Optional, default to no reminder. ) Reminder Tick. 0~60.");
        schema.put("reminder_day", "(Optional, needs reminder_tick, default to today if any reminder. ) Reminder day.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        String name, content;
        Integer reminderTick, reminderDay;
        {
            Object oname = args.get("name");
            Object ocontent = args.get("content");
            Object oreminderTick = args.get("reminder_tick");
            Object oreminderDay = args.get("reminder_day");
            if(!(oname instanceof String)){
                if(oname == null)
                    return "Error: write_note needs a note name";
                else
                    return "Error: name is not a string";
            }
            if(!(ocontent instanceof String)){
                if(ocontent == null)
                    return "write_note: Error: write_note needs note content";
                else
                    return "write_note: Error: content is not a string";
            }
            if(oreminderTick != null && !(oreminderTick instanceof Integer)){
                return "write_note: Error: reminder_tick is not a integer";
            }
            if(oreminderDay != null && !(oreminderDay instanceof Integer)){
                return "write_note: Error: reminder_day is not a integer";
            }
            name = (String) oname;
            content = (String) ocontent;
            reminderTick = (Integer) oreminderTick;
            reminderDay = (Integer) oreminderDay;
        }
        this.noteStore.writeNote(name, content, reminderTick, reminderDay);
        return "write_note: Write note successfully";
    }
}
