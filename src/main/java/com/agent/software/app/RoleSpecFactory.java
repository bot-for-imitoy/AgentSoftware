package com.agent.software.app;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.LlmPort;
import com.agent.software.utils.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Creates a new {@link RoleSpec} from a natural-language hiring requirement using
 * the LLM, without depending on the legacy {@code RoleFactory}/{@code AgentRole}.
 *
 * <p>The generated role uses the configured default toolkits and a name from the
 * bundled pool. Duplicate role ids get a numeric suffix.
 */
public final class RoleSpecFactory {

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BRACES = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private static final List<String> NAME_POOL = List.of(
            "Wang Jianguo", "Li Ming", "Zhang Wei", "Liu Yang", "Zhao Qiang", "Chen Jing",
            "Sun Xiao", "Zhou Mei", "Wu Xin", "Zheng Li", "Qian Feng", "Feng Tao",
            "Jiang Hua", "Shen Fang", "Han Lei", "Yang Xue", "Zhu Yong", "Qin Feng",
            "Xu Liang", "He Ying", "Lv Gang", "Shi Hui", "Wei Ran", "Su Jie");

    private static final String SYSTEM_PROMPT =
            "You are a professional HR specialist who creates precise role definitions from requirements. "
                    + "Output only JSON.";

    private final LlmPort llm;
    private final List<String> defaultToolkits;
    private final Supplier<List<RoleSpec>> roster;
    private final Set<String> usedNames = new LinkedHashSet<>();

    public RoleSpecFactory(LlmPort llm, List<String> defaultToolkits, Supplier<List<RoleSpec>> roster) {
        this.llm = llm;
        this.defaultToolkits = defaultToolkits == null ? List.of() : List.copyOf(defaultToolkits);
        this.roster = roster == null ? List::of : roster;
    }

    public RoleSpec create(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            throw new AgentException.ConfigException("hiring requirement must not be blank");
        }
        String prompt = buildPrompt(requirement);
        LlmPort.ChatReply reply = llm.chat(new LlmPort.ChatRequest(SYSTEM_PROMPT, prompt, 0.3, 512));
        if (reply.failed()) {
            throw new AgentException.PortException("role generation failed: " + reply.text());
        }
        Map<String, Object> config = parseJson(reply.text());
        if (config == null) {
            throw new AgentException.PortException(
                    "could not parse a role definition from the model response");
        }
        for (String field : List.of("role_id", "title", "responsibilities", "personality", "skills")) {
            if (!config.containsKey(field)) {
                throw new AgentException.PortException("generated role is missing field '" + field + "'");
            }
        }
        String roleId = uniqueId(String.valueOf(config.get("role_id")));
        return new RoleSpec(
                RoleId.of(roleId),
                nextName(),
                "",
                0,
                Json.str(config, "title", ""),
                Json.str(config, "responsibilities", ""),
                Json.str(config, "personality", ""),
                Json.strList(config, "skills"),
                Json.str(config, "system_prompt_extra", ""),
                "",
                "",
                "podman",
                Payload.empty(),
                defaultToolkits);
    }

    private String buildPrompt(String requirement) {
        List<RoleSpec> existing = roster.get();
        List<Map<String, Object>> reference = new ArrayList<>();
        for (RoleSpec spec : existing) {
            List<String> skills = spec.skills().size() > 5 ? spec.skills().subList(0, 5) : spec.skills();
            reference.add(Map.of("role_id", spec.id().value(), "title", spec.title(), "skills", skills));
        }
        return "Existing role templates (format reference):\n" + Json.stringifyPretty(reference)
                + "\n\nHiring requirement:\n" + requirement
                + "\n\nOutput JSON with keys role_id (lowercase_with_underscores), title, responsibilities, "
                + "personality, skills (>=5), system_prompt_extra (optional). Output only JSON.";
    }

    private String uniqueId(String raw) {
        String base = raw == null || raw.isBlank() ? "new_role" : raw.strip().replaceAll("[^a-zA-Z0-9_]", "_");
        Set<String> taken = new LinkedHashSet<>();
        for (RoleSpec spec : roster.get()) {
            taken.add(spec.id().value());
        }
        if (!taken.contains(base)) {
            return base;
        }
        int suffix = 1;
        while (taken.contains(base + "_" + suffix)) {
            suffix++;
        }
        return base + "_" + suffix;
    }

    /** Next unused person name, or {@code Employee NNN} once the pool is exhausted. */
    public synchronized String nextName() {
        if (usedNames.isEmpty()) {
            for (RoleSpec spec : roster.get()) {
                usedNames.add(spec.name());
            }
        }
        for (String candidate : NAME_POOL) {
            if (usedNames.add(candidate)) {
                return candidate;
            }
        }
        String generated = "Employee " + String.format("%03d", usedNames.size() + 1);
        usedNames.add(generated);
        return generated;
    }

    static Map<String, Object> parseJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        Map<String, Object> direct = tryParse(trimmed);
        if (direct != null) {
            return direct;
        }
        Matcher block = JSON_BLOCK.matcher(trimmed);
        if (block.find()) {
            Map<String, Object> parsed = tryParse(block.group(1).strip());
            if (parsed != null) {
                return parsed;
            }
        }
        Matcher braces = JSON_BRACES.matcher(trimmed);
        if (braces.find()) {
            return tryParse(braces.group(0));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tryParse(String text) {
        try {
            Object value = Json.parse(text);
            return value instanceof Map ? (Map<String, Object>) value : null;
        } catch (Exception e) {
            return null;
        }
    }
}
