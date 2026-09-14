package com.agent.software.adapters.persistence;

import com.agent.software.ports.NoteRepository;
import com.agent.software.store.NoteStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link NoteRepository} backed by the existing JSON {@code NoteStore}.
 * One store (and therefore one directory) per role, created lazily.
 */
public final class JsonNoteRepository implements NoteRepository {

    private final String baseDir;
    private final Map<String, NoteStore> stores = new ConcurrentHashMap<>();

    public JsonNoteRepository(Path baseDir) {
        this.baseDir = (baseDir == null ? Path.of("data", "notes") : baseDir).toString();
    }

    private NoteStore store(String roleId) {
        String key = roleId == null || roleId.isBlank() ? "shared" : roleId;
        return stores.computeIfAbsent(key, r -> new NoteStore(baseDir, r, null));
    }

    @Override
    public List<String> list(String roleId) {
        return store(roleId).listNotes();
    }

    @Override
    public void write(String roleId, String title, String content) {
        store(roleId).writeNote(title, content, null, null);
    }

    @Override
    public Optional<String> read(String roleId, String title) {
        return Optional.ofNullable(store(roleId).readNote(title));
    }

    @Override
    public boolean delete(String roleId, String title) {
        return store(roleId).deleteNote(title);
    }

    @Override
    public void writeSummary(String roleId, int day, String content) {
        store(roleId).saveSummary(content, day);
    }

    @Override
    public Optional<String> summary(String roleId, int day) {
        return Optional.ofNullable(store(roleId).getSummary(day));
    }

    @Override
    public Optional<String> latestSummary(String roleId, Integer beforeDay) {
        return Optional.ofNullable(store(roleId).getLatestSummary(beforeDay));
    }
}
