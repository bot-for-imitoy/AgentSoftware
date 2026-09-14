package com.agent.software.tools.builtin;

import com.agent.software.domain.Payload;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.SkillRepository;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.ToolService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillToolkitTest {

    private static final RoleId CEO = RoleId.of("CEO");

    static final class FakeSkills implements SkillRepository {
        final List<SkillInfo> skills = new ArrayList<>();

        FakeSkills add(String name, String description) {
            skills.add(new SkillInfo(name, description, name.toLowerCase().replace(" ", "_")));
            return this;
        }

        @Override
        public List<SkillInfo> available() {
            return List.copyOf(skills);
        }

        @Override
        public List<SkillInfo> search(String keyword) {
            return skills.stream()
                    .filter(s -> (s.name() + " " + s.description()).toLowerCase().contains(keyword.toLowerCase()))
                    .toList();
        }

        @Override
        public Optional<String> readSkill(String name) {
            return skills.stream().filter(s -> s.name().equals(name)).findFirst()
                    .map(s -> "SKILL.md of " + s.name());
        }
    }

    private static ToolResult call(ToolService service, String tool, Map<String, Object> args) {
        return service.invoke(CEO, new ToolCall("c", tool, Payload.of(args)));
    }

    @Test
    void listSearchAndInstallASkill() {
        FakeSkills skills = new FakeSkills().add("Pptx Generator", "make slide decks").add("Pdf Tool", "merge pdfs");
        ToolService service = new ToolService();
        service.bind(CEO, List.of(SkillToolkit.create(service, skills)));

        assertTrue(call(service, "skill_list", Map.of()).text().contains("Pptx Generator"));
        assertTrue(call(service, "skill_search", Map.of("keyword", "pdf")).text().contains("Pdf Tool"));

        ToolResult installed = call(service, "skill_add", Map.of("skill_name", "Pptx Generator"));
        assertTrue(installed.ok(), installed.text());
        assertTrue(service.hasTool(CEO, "pptx_generator"));
        assertTrue(call(service, "skill_my_skills", Map.of()).text().contains("Pptx Generator"));

        // the installed skill tool returns the SKILL.md content
        ToolResult invoked = service.invoke(CEO, new ToolCall("c", "pptx_generator", Payload.empty()));
        assertTrue(invoked.ok());
        assertTrue(invoked.text().contains("SKILL.md of Pptx Generator"));

        assertTrue(call(service, "skill_remove", Map.of("skill_name", "Pptx Generator")).ok());
        assertFalse(service.hasTool(CEO, "pptx_generator"));
    }

    @Test
    void unknownSkillFails() {
        ToolService service = new ToolService();
        service.bind(CEO, List.of(SkillToolkit.create(service, new FakeSkills())));
        assertFalse(call(service, "skill_add", Map.of("skill_name", "ghost")).ok());
        assertFalse(call(service, "skill_remove", Map.of("skill_name", "ghost")).ok());
        assertFalse(call(service, "skill_search", Map.of()).ok());
    }
}
