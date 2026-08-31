package com.maf.scheduler.role;

import com.maf.scheduler.llm.DeepSeekLLM;
import com.maf.scheduler.llm.LLM;
import com.maf.scheduler.llm.OllamaLLM;
import com.maf.scheduler.utils.Json;
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

    public RoleFactory(String apiKey, String model, String provider) {
        String p = provider != null ? provider
                : System.getenv().getOrDefault("LLM_PROVIDER", "deepseek");
        if ("ollama".equals(p)) {
            this.llm = new OllamaLLM(null, null, model, "role_factory", null);
        } else {
            this.llm = new DeepSeekLLM(apiKey, null, model, null, "role_factory", null);
        }
    }

    public RoleFactory() {
        this(null, null, null);
    }

    private static final String CREATE_ROLE_PROMPT = """
            你是一个 HR 专员，需要根据用人需求创建一个新的团队成员角色。

            现有角色模板（参考格式）：
            {existing_templates}

            用人需求：
            {requirement}

            请根据需求创建一个新角色，输出 JSON 格式：
            ```json
            {{
                "role_id": "英文小写下划线，如 rust_engineer",
                "title": "职位名称",
                "responsibilities": "职责描述（中文，一句话概括主要工作内容）",
                "personality": "性格特点（中文，2-3句）",
                "skills": ["技能1", "技能2", ...],
                "interest_keywords": ["关键词1", "关键词2", ...],
                "system_prompt_extra": "额外的系统提示（可选，如输出格式要求）"
            }}
            ```

            注意：
            1. role_id 不要与现有模板重复
            2. interest_keywords 要包含中英文关键词
            3. skills 至少 5 个
            4. 关键词至少 6 个
            5. 仅输出 JSON，不要其他内容""";

    /** 从用人需求创建新角色. */
    public AgentRole createRole(String requirement) {
        // 构建现有模板列表供 LLM 参考
        List<Map<String, Object>> existing = new ArrayList<>();
        for (Map.Entry<String, java.util.function.Supplier<AgentRole>> e : RoleTemplates.TEMPLATES.entrySet()) {
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
                "你是一个专业的 HR 专员，擅长根据需求创建精准的角色定义。仅输出 JSON。",
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
        String personName = RoleTemplates.nextName();
        String generatedRoleId = String.valueOf(roleConfig.get("role_id"));
        if (RoleTemplates.TEMPLATES.containsKey(generatedRoleId)) {
            String base = generatedRoleId;
            int dup = 1;
            while (RoleTemplates.TEMPLATES.containsKey(generatedRoleId)) {
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
        RoleTemplates.addTemplate(role);
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
