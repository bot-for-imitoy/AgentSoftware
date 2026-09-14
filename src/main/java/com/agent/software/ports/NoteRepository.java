package com.agent.software.ports;

import java.util.List;
import java.util.Optional;

/**
 * Per-role note and daily-summary storage.
 *
 * <p>Reminder scheduling is intentionally not part of this port: notes are data,
 * reminders are clock entries owned by a reminder service. This keeps the
 * repository free of {@code TimeEventBus}-style coupling.
 */
public interface NoteRepository {

    List<String> list(String roleId);

    void write(String roleId, String title, String content);

    Optional<String> read(String roleId, String title);

    boolean delete(String roleId, String title);

    void writeSummary(String roleId, int day, String content);

    Optional<String> summary(String roleId, int day);

    /** Most recent summary strictly before {@code beforeDay} (null = latest overall). */
    Optional<String> latestSummary(String roleId, Integer beforeDay);
}
