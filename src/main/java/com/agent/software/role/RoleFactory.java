package com.agent.software.role;

import com.agent.software.llm.LLM;
import com.agent.software.llm.OpenAICompatLLM;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RoleFactory — LLM 驱动的角色创建 (从用人需求, Python 版 role_factory.py).
 *
 * 用法:
 *     RoleFactory factory = new RoleFactory();
 *     AgentRole newRole = factory.createRole("需要一位精通 Rust 的后端工程师，熟悉 gRPC 和 PostgreSQL");
 */
public class RoleFactory {

    private static final Logger logger = LoggerFactory.getLogger(RoleFactory.class);

    private final LLM llm;

    public RoleFactory(String apiKey, String model) {
        this.llm = new OpenAICompatLLM(apiKey, null, model, "role_factory", null);
    }

    public RoleFactory() {
        this(null, null);
    }

    private static final String CREATE_ROLE_PROMPT = """
            You are an HR specialist. Create a new team member role based on the hiring requirement.

            Existing role templates (for format reference):
            {existing_templates}

            Hiring requirement:
            {requirement}

            Create a new role based on the requirement, and output JSON in the following format:
            ```json
            {{
                "role_id": "lowercase English with underscores, e.g. rust_engineer",
                "title": "job title",
                "responsibilities": "responsibilities (one sentence summarizing the main work)",
                "personality": "personality (2-3 sentences)",
                "skills": ["skill1", "skill2", ...],
                "interest_keywords": ["keyword1", "keyword2", ...],
                "system_prompt_extra": "extra system prompt (optional, e.g. output format requirements)"
            }}
            ```

            Notes:
            1. role_id must not duplicate existing templates
            2. interest_keywords should include both English and Chinese keywords
            3. at least 5 skills
            4. at least 6 keywords
            5. output only JSON, nothing else""";

    /** 从用人需求创建新角色. */
    public AgentRole createRole(String requirement) {
        // 构建现有模板列表供 LLM 参考
        List<Map<String, Object>> existing = new ArrayList<>();
        for (Map.Entry<String, java.util.function.Supplier<AgentRole>> e : RoleLoader.TEMPLATES.entrySet()) {
            AgentRole r = e.getValue().get();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role_id", r.roleId);
            m.put("title", r.title);
            m.put("skills", r.skills.size() > 5 ? r.skills.subList(0, 5) : r.skills);
            List<String> kw = new ArrayList<>(r.interestKeywords);
            kw.sort(String::compareTo);
            m.put("keywords", kw.size() > 5 ? kw.subList(0, 5) : kw);
            existing.add(m);
        }
        String prompt = CREATE_ROLE_PROMPT
                .replace("{existing_templates}", Json.stringifyPretty(existing))
                .replace("{requirement}", requirement);

        logger.info("RoleFactory: generating role for requirement: {}", truncate(requirement, 80));
        LLM.ChatResponse resp = llm.chat(
                "You are a professional HR specialist who excels at creating precise role definitions from requirements. Output only JSON.",
                prompt, 0.3, 512);

        Map<String, Object> roleConfig = parseJson(resp.text);
        if (roleConfig == null) {
            throw new IllegalArgumentException(
                    "Failed to parse role config from LLM response: " + truncate(resp.text, 200));
        }
        // 校验必填字段
        String[] required = {"role_id", "title", "responsibilities", "personality", "skills", "interest_keywords"};
        for (String f : required) {
            if (!roleConfig.containsKey(f)) {
                throw new IllegalArgumentException("Missing required field '" + f + "' in role config");
            }
        }
        // 生成唯一人名与 role_id
        String personName = RoleLoader.nextName();
        String generatedRoleId = String.valueOf(roleConfig.get("role_id"));
        if (RoleLoader.TEMPLATES.containsKey(generatedRoleId)) {
            String base = generatedRoleId;
            int dup = 1;
            while (RoleLoader.TEMPLATES.containsKey(generatedRoleId)) {
                generatedRoleId = base + "_" + dup;
                dup++;
            }
        }
        Set<String> keywords = new LinkedHashSet<>();
        for (String k : Json.strList(roleConfig, "interest_keywords")) {
            keywords.add(k);
        }
        AgentRole role = AgentRole.builder()
                .name(personName)
                .roleId(generatedRoleId)
                .title(Json.str(roleConfig, "title", ""))
                .responsibilities(Json.str(roleConfig, "responsibilities", ""))
                .personality(Json.str(roleConfig, "personality", ""))
                .skills(Json.strList(roleConfig, "skills"))
                .interestKeywords(keywords)
                .systemPromptExtra(Json.str(roleConfig, "system_prompt_extra", ""))
                .build();
        // 注册进模板池
        RoleLoader.addTemplate(role);
        logger.info("RoleFactory: created role '{}' ({}) — {}, {} skills, {} keywords, {} tokens",
                generatedRoleId, personName, role.title, role.skills.size(),
                role.interestKeywords.size(), resp.tokens);
        return role;
    }

    private static final Pattern JSON_BLOCK = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BRACES = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    /** 从 LLM 响应提取 JSON (处理 ```json 块). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseJson(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        // 直接解析
        try {
            Object v = Json.parse(t);
            if (v instanceof Map) {
                return (Map<String, Object>) v;
            }
        } catch (Exception ignored) {
        }
        // ```json ... ``` 块
        Matcher m = JSON_BLOCK.matcher(t);
        if (m.find()) {
            try {
                Object v = Json.parse(m.group(1).strip());
                if (v instanceof Map) {
                    return (Map<String, Object>) v;
                }
            } catch (Exception ignored) {
            }
        }
        // { ... } 提取
        m = JSON_BRACES.matcher(t);
        if (m.find()) {
            try {
                Object v = Json.parse(m.group(0));
                if (v instanceof Map) {
                    return (Map<String, Object>) v;
                }
            } catch (Exception ignored) {
            }
        }
        logger.warn("Failed to parse JSON from: {}", truncate(text, 200));
        return null;
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() > n ? s.substring(0, n) : s;
    }
}
