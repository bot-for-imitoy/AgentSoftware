package com.agent.software.tools.builtin;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.ports.NoteRepository;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.List;
import java.util.Optional;

/**
 * The {@code note} toolkit: the role's external memory.
 *
 * <p>Reminder scheduling ({@code reminder_tick}) is not part of this toolkit yet;
 * notes are stored as plain markdown and reminders will be added with the
 * reminder service.
 */
public final class NoteToolkit {

    private NoteToolkit() {
    }

    public static Toolkit create(NoteRepository repo) {
        Tool write = Tools.of("write_note", "Write a note (overwrites an existing note with the same name)",
                JsonSchema.builder()
                        .required("name", JsonSchema.Property.string("The name of this note."))
                        .required("content", JsonSchema.Property.string("The content of your note."))
                        .build(),
                (role, call) -> {
                    String name = Tools.argStripped(call, "name");
                    String content = Tools.arg(call, "content");
                    if (name.isEmpty()) {
                        return ToolResult.error("write_note: Error: needs a note name");
                    }
                    if (content.isBlank()) {
                        return ToolResult.error("write_note: Error: needs note content");
                    }
                    repo.write(role.value(), name, content);
                    return ToolResult.success("write_note: note saved: " + name);
                });

        Tool read = Tools.of("read_note", "Read a note",
                JsonSchema.builder()
                        .required("name", JsonSchema.Property.string("The name of the note to read."))
                        .build(),
                (role, call) -> {
                    String name = Tools.argStripped(call, "name");
                    if (name.isEmpty()) {
                        return ToolResult.error("read_note: Error: needs a note name");
                    }
                    Optional<String> content = repo.read(role.value(), name);
                    return content.map(ToolResult::success)
                            .orElseGet(() -> ToolResult.error("read_note: Note not found: " + name));
                });

        Tool list = Tools.of("list_notes", "List your notes",
                JsonSchema.object(),
                (role, call) -> {
                    List<String> titles = repo.list(role.value());
                    if (titles.isEmpty()) {
                        return ToolResult.success("list_notes: (no notes yet)");
                    }
                    StringBuilder sb = new StringBuilder("list_notes:");
                    for (String title : titles) {
                        sb.append("\n- ").append(title);
                    }
                    return ToolResult.success(sb.toString());
                });

        Tool edit = Tools.of("edit_note", "Replace the content of an existing note",
                JsonSchema.builder()
                        .required("name", JsonSchema.Property.string("The name of the note to edit."))
                        .required("content", JsonSchema.Property.string("The new content of the note."))
                        .build(),
                (role, call) -> {
                    String name = Tools.argStripped(call, "name");
                    String content = Tools.arg(call, "content");
                    if (name.isEmpty() || content.isBlank()) {
                        return ToolResult.error("edit_note: Error: needs name and content");
                    }
                    boolean existed = repo.read(role.value(), name).isPresent();
                    repo.write(role.value(), name, content);
                    return ToolResult.success("edit_note: note " + (existed ? "updated" : "created") + ": " + name);
                });

        Tool delete = Tools.of("delete_note", "Delete a note",
                JsonSchema.builder()
                        .required("name", JsonSchema.Property.string("The name of the note to delete."))
                        .build(),
                (role, call) -> {
                    String name = Tools.argStripped(call, "name");
                    if (name.isEmpty()) {
                        return ToolResult.error("delete_note: Error: needs a note name");
                    }
                    return repo.delete(role.value(), name)
                            ? ToolResult.success("delete_note: note deleted: " + name)
                            : ToolResult.error("delete_note: Note not found: " + name);
                });

        return new Toolkit("note", "Note toolkit: write/read/list/edit/delete the role's notes",
                List.of(write, read, list, edit, delete));
    }
}
