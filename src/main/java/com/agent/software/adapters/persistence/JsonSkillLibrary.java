package com.agent.software.adapters.persistence;

import com.agent.software.kernel.Names;
import com.agent.software.ports.SkillRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Directory-backed {@link SkillRepository}: recursively finds {@code SKILL.md}
 * files and parses their name/description frontmatter. Standalone (no legacy
 * {@code SkillManager} dependency).
 */
public final class JsonSkillLibrary implements SkillRepository {

    private record Entry(String name, String description, String toolName, Path dir) {
    }

    private final Path skillsDir;
    private Map<String, Entry> loaded;

    public JsonSkillLibrary(Path skillsDir) {
        this.skillsDir = skillsDir == null ? Path.of("data", "skills") : skillsDir;
    }

    private synchronized Map<String, Entry> entries() {
        if (loaded != null) {
            return loaded;
        }
        Map<String, Entry> out = new LinkedHashMap<>();
        if (Files.isDirectory(skillsDir)) {
            try (var stream = Files.walk(skillsDir)) {
                List<Path> files = stream
                        .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                        .sorted()
                        .toList();
                for (Path file : files) {
                    String text;
                    try {
                        text = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        continue;
                    }
                    String[] front = parseFrontmatter(text);
                    String name = front[0] == null || front[0].isBlank()
                            ? file.getParent().getFileName().toString()
                            : front[0];
                    String unique = name;
                    int suffix = 2;
                    while (out.containsKey(unique)) {
                        unique = name + "-" + suffix++;
                    }
                    out.put(unique, new Entry(unique, front[1], Names.toolName(unique), file.getParent()));
                }
            } catch (IOException ignored) {
                // an unreadable library is simply empty
            }
        }
        loaded = out;
        return out;
    }

    @Override
    public List<SkillInfo> available() {
        List<SkillInfo> out = new ArrayList<>();
        for (Entry entry : entries().values()) {
            out.add(new SkillInfo(entry.name(), entry.description(), entry.toolName()));
        }
        return out;
    }

    @Override
    public List<SkillInfo> search(String keyword) {
        String kw = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        if (kw.isEmpty()) {
            return List.of();
        }
        List<SkillInfo> out = new ArrayList<>();
        for (Entry entry : entries().values()) {
            String haystack = (entry.name() + " " + entry.description()).toLowerCase(Locale.ROOT);
            if (haystack.contains(kw)) {
                out.add(new SkillInfo(entry.name(), entry.description(), entry.toolName()));
            }
        }
        return out;
    }

    @Override
    public Optional<String> readSkill(String name) {
        Entry entry = entries().get(name);
        if (entry == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(entry.name())
                .append("\nDirectory: ").append(entry.dir())
                .append("\nDescription: ").append(entry.description())
                .append("\n\n");
        String body = readSkillMd(entry.dir());
        sb.append(body.isEmpty() ? "(SKILL.md is empty)" : body);
        List<String> related = relatedFiles(entry.dir());
        if (!related.isEmpty()) {
            sb.append("\n\nRelated files (accessible via run_command):");
            for (String file : related) {
                sb.append("\n- ").append(file);
            }
        }
        return Optional.of(sb.toString());
    }

    private static String readSkillMd(Path dir) {
        try {
            return Files.readString(dir.resolve("SKILL.md"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static List<String> relatedFiles(Path dir) {
        List<String> out = new ArrayList<>();
        for (String sub : List.of("scripts", "references", "assets")) {
            Path parent = dir.resolve(sub);
            if (!Files.isDirectory(parent)) {
                continue;
            }
            try (var stream = Files.walk(parent)) {
                stream.filter(Files::isRegularFile).sorted()
                        .forEach(path -> out.add(dir.relativize(path).toString()));
            } catch (IOException ignored) {
                // skip unreadable directories
            }
        }
        return out;
    }

    static String[] parseFrontmatter(String text) {
        if (text == null || !text.startsWith("---")) {
            return new String[]{null, ""};
        }
        int end = text.indexOf("\n---", 3);
        if (end == -1) {
            end = text.indexOf("...", 3);
        }
        String front = end != -1 ? text.substring(3, end) : text.substring(3);
        String name = null;
        String description = "";
        for (String line : front.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("name:")) {
                name = trimmed.substring("name:".length()).strip().replaceAll("^[\"']|[\"']$", "");
            } else if (trimmed.startsWith("description:") && description.isEmpty()) {
                description = trimmed.substring("description:".length()).strip()
                        .replaceAll("^[\"']|[\"']$", "");
            }
        }
        return new String[]{name, description};
    }
}
