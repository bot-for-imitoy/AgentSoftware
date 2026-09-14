package com.agent.software.adapters.persistence;

import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.Names;
import com.agent.software.ports.NoteRepository;
import com.agent.software.kernel.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed {@link NoteRepository}: {@code <base>/<role>/notes/<title>.md} and
 * {@code <base>/<role>/summaries/<day>.md}. No legacy store dependency.
 */
public final class JsonNoteRepository implements NoteRepository {

    private final Path base;

    public JsonNoteRepository(Path base) {
        this.base = base == null ? Path.of("data", "notes") : base;
    }

    private Path roleDir(String roleId) {
        return base.resolve(Names.sanitize(roleId == null || roleId.isBlank() ? "shared" : roleId));
    }

    private Path notesDir(String roleId) {
        return roleDir(roleId).resolve("notes");
    }

    private Path summariesDir(String roleId) {
        return roleDir(roleId).resolve("summaries");
    }

    private static Path noteFile(Path notesDir, String title) {
        return notesDir.resolve(Names.sanitize(title) + ".md");
    }

    @Override
    public List<String> list(String roleId) {
        return listMarkdown(notesDir(roleId));
    }

    @Override
    public void write(String roleId, String title, String content) {
        Path file = noteFile(notesDir(roleId), title);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentException.PortException("cannot write note: " + file, e);
        }
    }

    @Override
    public Optional<String> read(String roleId, String title) {
        Path file = noteFile(notesDir(roleId), title);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AgentException.PortException("cannot read note: " + file, e);
        }
    }

    @Override
    public boolean delete(String roleId, String title) {
        try {
            return Files.deleteIfExists(noteFile(notesDir(roleId), title));
        } catch (IOException e) {
            throw new AgentException.PortException("cannot delete note: " + title, e);
        }
    }

    @Override
    public void writeSummary(String roleId, int day, String content) {
        Path file = summariesDir(roleId).resolve(Math.max(1, day) + ".md");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AgentException.PortException("cannot write summary: " + file, e);
        }
    }

    @Override
    public Optional<String> summary(String roleId, int day) {
        return readFile(summariesDir(roleId).resolve(Math.max(1, day) + ".md"));
    }

    @Override
    public Optional<String> latestSummary(String roleId, Integer beforeDay) {
        Path dir = summariesDir(roleId);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        List<Integer> days = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        try {
                            days.add(Integer.parseInt(name.substring(0, name.length() - 3)));
                        } catch (NumberFormatException ignored) {
                            // not a day summary
                        }
                    });
        } catch (IOException e) {
            return Optional.empty();
        }
        days.sort(Comparator.reverseOrder());
        for (int day : days) {
            if (beforeDay == null || day < beforeDay) {
                Optional<String> content = summary(roleId, day);
                if (content.isPresent()) {
                    return content;
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> listMarkdown(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .sorted()
                    .forEach(name -> out.add(name.substring(0, name.length() - 3)));
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    private static Optional<String> readFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
