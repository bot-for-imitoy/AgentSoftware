package com.agent.software.adapters.persistence;

import com.agent.software.ports.SkillRepository;
import com.agent.software.tools.toolkits.skill.SkillManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** {@link SkillRepository} over the existing SKILL.md {@code SkillManager}. */
public final class JsonSkillLibrary implements SkillRepository {

    private final SkillManager manager;

    public JsonSkillLibrary(SkillManager manager) {
        if (manager == null) {
            throw new IllegalArgumentException("manager must not be null");
        }
        this.manager = manager;
    }

    @Override
    public List<SkillInfo> available() {
        List<SkillInfo> out = new ArrayList<>();
        for (SkillManager.SkillInfo info : manager.ensureLoaded().values()) {
            out.add(convert(info));
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
        for (SkillManager.SkillInfo info : manager.ensureLoaded().values()) {
            String haystack = (info.name + " " + (info.description == null ? "" : info.description))
                    .toLowerCase(Locale.ROOT);
            if (haystack.contains(kw)) {
                out.add(convert(info));
            }
        }
        return out;
    }

    @Override
    public Optional<String> readSkill(String name) {
        SkillManager.SkillInfo info = manager.ensureLoaded().get(name);
        if (info == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Skill: ").append(info.name)
                .append("\nDirectory: ").append(info.path)
                .append("\nDescription: ").append(info.description)
                .append("\n\n");
        String body = info.readSkillMd();
        sb.append(body == null || body.isEmpty() ? "(SKILL.md is empty)" : body);
        List<String> related = info.listRelatedFiles();
        if (!related.isEmpty()) {
            sb.append("\n\nRelated files (accessible via run_command):");
            for (String file : related) {
                sb.append("\n- ").append(file);
            }
        }
        return Optional.of(sb.toString());
    }

    private static SkillInfo convert(SkillManager.SkillInfo info) {
        return new SkillInfo(info.name, info.description, info.toolName());
    }
}
