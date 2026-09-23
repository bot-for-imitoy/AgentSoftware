package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.llm.LLM;
import com.agent.software.llm.OpenAICompatLLM;
import com.agent.software.llm.Response;
import com.agent.software.llm.context.Context;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HR 招聘：从一段招聘要求生成一名新员工的档案，并登记进公司员工名单（{@link CompanyRoster}）。
 *
 * <p>生成策略：有可用 API Key 时让 LLM 按固定 JSON 结构生成（用**独立的 LLM + Context**，
 * 不污染 HR 角色自己的对话上下文）；没有 Key 或解析失败时退回本地确定性生成，
 * 保证 `post_job_posting` 永远能产出一份合法员工信息。
 *
 * <p>生成的是"名单里的人"（{@link MembershipState#OUT_OF_GROUP}），要真正进组由 COO 的
 * {@code draft_in} 决定。
 */
public final class RoleFactory {

    private static final Logger logger = LoggerFactory.getLogger(RoleFactory.class);

    private static final String[] NAME_POOL = {
            "Li Wei", "Zhang Min", "Wang Fang", "Liu Yang", "Chen Jie",
            "Yang Fan", "Zhao Lei", "Huang Rui", "Zhou Xin", "Wu Hao",
            "Xu Ning", "Sun Qian", "Ma Chao", "Zhu Lin", "Hu Yue",
            "Guo Peng", "He Ping", "Gao Shan", "Lin Tao", "Luo Na",
    };

    private static final String SYSTEM_PROMPT =
            "You are a professional HR specialist. Create a precise role definition from a hiring "
                    + "requirement and output ONLY a JSON object.";

    private static final String PROMPT_TEMPLATE = """
            Existing roles (for reference, do not duplicate role_id):
            {existing}

            Hiring requirement:
            {requirement}

            Output JSON with exactly these fields:
            {
              "role_id": "lowercase english with underscores, e.g. rust_engineer",
              "title": "job title",
              "group": "one of the existing groups listed above, or a short new group name",
              "responsibilities": "one sentence",
              "personality": "2-3 sentences",
              "skills": ["at least 5 skills"],
              "interest_keywords": ["at least 6 keywords"],
              "system_prompt_extra": "optional extra instructions"
            }
            Output only JSON, nothing else.""";

    private static final Pattern FENCED = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);
    private static final Pattern BRACES = Pattern.compile("\\{.*\\}", Pattern.DOTALL);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#._-]{2,}");

    private final AgentSystem system;

    public RoleFactory(AgentSystem system) {
        this.system = system;
    }

    /** 生成并登记一名员工；返回名单里的条目。 */
    public Employee createEmployee(String requirement) {
        String req = requirement == null ? "" : requirement.strip();
        Map<String, Object> config = fromLlm(req);
        if (config == null) {
            logger.info("RoleFactory: using local generator for requirement: {}", truncate(req, 80));
            return register(localProfile(req), req);
        }
        return register(config, req);
    }

    // ── LLM 生成 ────────────────────────────────────────────────

    private Map<String, Object> fromLlm(String requirement) {
        if (!hasApiKey()) {
            return null;
        }
        try {
            LLM llm = new OpenAICompatLLM(null, null, system.getConfigStore());
            Context ctx = new Context();
            llm.setContext(ctx);
            llm.setSystemPrompt(SYSTEM_PROMPT);
            llm.appendUserMessage(PROMPT_TEMPLATE
                    .replace("{existing}", Json.stringifyPretty(existingRoles()))
                    .replace("{requirement}", requirement));
            Response r = llm.request();
            Map<String, Object> parsed = parseJson(r.text);
            if (parsed == null || parsed.get("role_id") == null || parsed.get("title") == null) {
                logger.warn("RoleFactory: LLM output unusable, falling back");
                return null;
            }
            return parsed;
        } catch (Exception e) {
            logger.warn("RoleFactory: LLM generation failed, falling back: {}", e.toString());
            return null;
        }
    }

    private boolean hasApiKey() {
        String key = System.getenv().getOrDefault("OPENAI_API_KEY", "");
        if (!key.isBlank()) {
            return true;
        }
        if (system.getConfigStore() == null) {
            return false;
        }
        Object cfg = system.getConfigStore().get("llm.api_key", null);
        return cfg != null && !String.valueOf(cfg).isBlank();
    }

    private List<Map<String, Object>> existingRoles() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Employee e : system.getRoster().all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role_id", e.roleId);
            m.put("title", e.templateString("title", ""));
            m.put("group", e.group);
            out.add(m);
            if (out.size() >= 30) {
                break;
            }
        }
        return out;
    }

    private static Map<String, Object> parseJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String candidate = text;
        Matcher m = FENCED.matcher(text);
        if (m.find()) {
            candidate = m.group(1);
        } else {
            Matcher b = BRACES.matcher(text);
            if (b.find()) {
                candidate = b.group();
            }
        }
        try {
            return Json.parseObject(candidate);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 本地兜底 ────────────────────────────────────────────────

    private Map<String, Object> localProfile(String requirement) {
        Set<String> skills = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(requirement);
        while (m.find() && skills.size() < 8) {
            String token = m.group();
            if (token.length() >= 3 && !isStopWord(token.toLowerCase(Locale.ROOT))) {
                skills.add(token);
            }
        }
        if (skills.isEmpty()) {
            skills.add("general");
        }
        String title = requirement.isBlank() ? "New Team Member"
                : truncate(requirement.replace('\n', ' ').strip(), 60);
        String roleSeed = skills.iterator().next().toLowerCase(Locale.ROOT);
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("role_id", roleSeed + "_specialist");
        cfg.put("title", title);
        cfg.put("group", inferGroup(requirement));
        cfg.put("responsibilities", "Own tasks related to: " + truncate(requirement, 120));
        cfg.put("personality", "Pragmatic, collaborative and detail-oriented; asks questions early.");
        cfg.put("skills", new ArrayList<>(skills));
        cfg.put("interest_keywords", new ArrayList<>(skills));
        cfg.put("system_prompt_extra", "");
        return cfg;
    }

    private static boolean isStopWord(String w) {
        return switch (w) {
            case "the", "and", "for", "with", "who", "that", "need", "needs", "must", "can",
                 "will", "has", "have", "our", "you", "are", "able", "熟悉", "精通" -> true;
            default -> false;
        };
    }

    private String inferGroup(String requirement) {
        String lower = requirement.toLowerCase(Locale.ROOT);
        for (Employee e : system.getRoster().all()) {
            if (e.group == null || e.group.isBlank()) {
                continue;
            }
            String shortName = e.group.replace(" Group", "").toLowerCase(Locale.ROOT);
            if (lower.contains(shortName) || lower.contains(e.group.toLowerCase(Locale.ROOT))) {
                return e.group;
            }
        }
        return "General Group";
    }

    // ── 登记 ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Employee register(Map<String, Object> cfg, String requirement) {
        String roleId = uniqueRoleId(str(cfg.get("role_id"), "new_hire"));
        String name = nextName();
        String group = str(cfg.get("group"), inferGroup(requirement));
        List<String> skills = strList(cfg.get("skills"));
        if (skills.isEmpty()) {
            skills = List.of("general");
        }
        List<String> keywords = strList(cfg.get("interest_keywords"));
        if (keywords.isEmpty()) {
            keywords = skills;
        }

        Employee employee = new Employee(roleId, name, group);
        employee.membership = MembershipState.OUT_OF_GROUP;
        Map<String, String> t = employee.template;
        t.put("role_id", roleId);
        t.put("name", name);
        t.put("group", group);
        t.put("username", roleId);
        t.put("title", str(cfg.get("title"), roleId));
        t.put("responsibilities", str(cfg.get("responsibilities"), ""));
        t.put("personality", str(cfg.get("personality"), ""));
        t.put("skills", Json.stringify(skills));
        t.put("interest_keywords", Json.stringify(keywords));
        t.put("system_prompt_extra", str(cfg.get("system_prompt_extra"), ""));
        t.put("uid", String.valueOf(1100 + system.getRoster().size() + 1));
        t.put("hired_for", requirement);

        system.getRoster().add(employee);
        logger.info("RoleFactory: hired '{}' ({}) — {}, group {}, {} skills",
                roleId, name, t.get("title"), group, skills.size());
        return employee;
    }

    private String uniqueRoleId(String raw) {
        String base = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("^_+|_+$", "");
        if (base.isBlank()) {
            base = "new_hire";
        }
        String candidate = base;
        int n = 1;
        while (system.getRoster().find(candidate) != null) {
            candidate = base + "_" + n++;
        }
        return candidate;
    }

    private String nextName() {
        for (String candidate : NAME_POOL) {
            if (system.getRoster().findByName(candidate) == null) {
                return candidate;
            }
        }
        return "New Hire " + (system.getRoster().size() + 1);
    }

    private static String str(Object o, String def) {
        if (o == null) {
            return def;
        }
        String s = String.valueOf(o).strip();
        return s.isEmpty() ? def : s;
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).strip());
                }
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
