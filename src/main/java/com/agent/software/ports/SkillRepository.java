package com.agent.software.ports;

import java.util.List;
import java.util.Optional;

/** Read access to the shared SKILL.md library. */
public interface SkillRepository {

    /** One skill package. */
    record SkillInfo(String name, String description, String toolName) {
        public SkillInfo {
            name = name == null ? "" : name;
            description = description == null ? "" : description;
            toolName = toolName == null || toolName.isBlank() ? "skill" : toolName;
        }
    }

    List<SkillInfo> available();

    List<SkillInfo> search(String keyword);

    /** Full SKILL.md text (plus related files) for one skill. */
    Optional<String> readSkill(String name);
}
