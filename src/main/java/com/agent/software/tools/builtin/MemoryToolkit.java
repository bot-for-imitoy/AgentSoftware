package com.agent.software.tools.builtin;

import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ClockPort;
import com.agent.software.ports.NoteRepository;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * The {@code memory} toolkit: the end-of-day summary.
 *
 * <p>Saving the summary is a lifecycle event, so the toolkit reports it through a
 * callback instead of reaching into the runtime itself.
 */
public final class MemoryToolkit {

    private MemoryToolkit() {
    }

    public static Toolkit create(NoteRepository notes, ClockPort clock,
                                 BiConsumer<RoleId, Integer> onSummarySaved) {
        Tool summary = Tools.of("summary", "Save the summary of today's work",
                JsonSchema.builder()
                        .required("content", JsonSchema.Property.string(
                                "The summary of today's work: work done, key decisions, unfinished items."))
                        .property("day", JsonSchema.Property.integer(
                                "(Optional) Day number; defaults to today."))
                        .build(),
                (role, call) -> {
                    String content = Tools.arg(call, "content");
                    if (content.isBlank()) {
                        return ToolResult.error("summary: Error: needs summary content");
                    }
                    int day = call.arguments().intVal("day").orElseGet(clock::day);
                    notes.writeSummary(role.value(), day, content);
                    if (onSummarySaved != null) {
                        onSummarySaved.accept(role, day);
                    }
                    return ToolResult.success("summary: Day " + day + " summary saved. "
                            + "You may now rest until the next shift.");
                });

        return new Toolkit("memory", "Memory toolkit: save the daily summary (injected as yesterday's context next day)",
                List.of(summary));
    }
}
