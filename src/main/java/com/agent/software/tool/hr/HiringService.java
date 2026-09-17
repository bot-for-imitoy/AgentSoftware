package com.agent.software.tool.hr;

import com.agent.software.agent.Staffing;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Text;
import com.agent.software.llm.LlmClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 驱动的招聘（master {@code RoleFactory} + HR 工具的上岗流程）。
 *
 * <p>{@link #draft(String)} 只产出 {@link RoleSpec}（纯决策，不落库）；
 * {@link #onboard(RoleSpec)} 才真正注册并启动，是唯一产生副作用的动作。
 *
 * <p>容错边界（对齐 master {@code RoleFactory.parseJson}）：LLM 输出允许是纯 JSON、
 * {@code ```json} 代码块、或夹在解释文字中的第一个 {@code {…}}；只要最终能解析出对象，
 * 缺 {@code skills}/{@code interest_keywords} 按空集合处理，{@code role_id} 缺失则用需求文本生成 slug。
 * 但 {@code title}/{@code responsibilities}/{@code personality} 三个核心字段缺失，或整段无法解析时，
 * 一律抛 {@code hr.draft.failed}，绝不返回半成品角色。
 */
public final class HiringService implements Recruiter {

    private static final Logger logger = LoggerFactory.getLogger(HiringService.class);

    private static final double DEFAULT_SALIENCE = 0.4;
    private static final Pattern JSON_BLOCK =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", Pattern.DOTALL);
    private static final Pattern JSON_BRACES = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT =
            "你是一名专业 HR，擅长根据招聘需求产出精确的岗位定义。只输出 JSON，不要输出多余文字。";

    private static final String USER_PROMPT = """
            你是一名 HR 专员。请根据招聘需求创建一个新的团队成员角色。

            现有角色模板（仅供格式参考）：
            {existing_templates}

            招聘需求：
            {requirement}

            请输出如下格式的 JSON：
            ```json
            {
              "role_id": "小写英文加下划线，例如 rust_engineer",
              "name": "中文姓名",
              "username": "小写拼音用户名，例如 wangjianguo",
              "title": "岗位名称",
              "responsibilities": "职责（一句话概括主要工作）",
              "personality": "性格（2-3 句）",
              "skills": ["技能1", "技能2"],
              "interest_keywords": ["关键词1", "关键词2"],
              "system_prompt_extra": "额外的系统提示（可选）",
              "group": "所属组"
            }
            ```

            注意：
            1. role_id 不得与现有模板重复
            2. interest_keywords 同时包含中英文关键词
            3. 至少 5 个 skills、6 个 interest_keywords
            4. 只输出 JSON，不要输出任何其他内容""";

    private final LlmClient llm;
    private final List<RoleSpec> templates;
    private final Staffing staffing;
    private final JacksonJsonCodec json = new JacksonJsonCodec();

    public HiringService(LlmClient llm, List<RoleSpec> templates, Staffing staffing) {
        this.llm = llm;
        this.templates = templates == null ? List.of() : templates;
        this.staffing = staffing;
    }

    @Override
    public List<RoleSpec> candidates() {
        return List.copyOf(templates);
    }

    @Override
    public RoleSpec draft(String requirement) {
        if (Text.isBlank(requirement)) {
            throw new DomainError("hr.draft.failed", "招聘需求为空，无法起草角色");
        }
        String prompt = USER_PROMPT
                .replace("{existing_templates}", templateCatalog())
                .replace("{requirement}", requirement.trim());

        LlmClient.ChatReply reply;
        try {
            reply = llm.chat(new LlmClient.ChatRequest(SYSTEM_PROMPT, prompt, 0.3, 1024));
        } catch (RuntimeException e) {
            throw new DomainError("hr.draft.failed", "调用 LLM 起草角色失败: " + e.getMessage(), e);
        }
        // 不依赖 ChatReply.failed()：它属于 llm 包、当前仍是骨架；空白文本已能覆盖绝大多数失败形态，
        // 其余带错误文案的回复会在 JSON 解析阶段被识别为 hr.draft.failed。
        if (reply == null || Text.isBlank(reply.text())) {
            throw new DomainError("hr.draft.failed", "LLM 未返回可用的角色定义（响应为空）");
        }

        Map<String, Object> config = parseJson(reply.text());
        if (config == null) {
            throw new DomainError("hr.draft.failed",
                    "无法从 LLM 输出解析角色 JSON: " + Text.truncate(Text.squashWhitespace(reply.text()), 200));
        }
        List<String> missing = new ArrayList<>();
        for (String field : List.of("title", "responsibilities", "personality")) {
            if (Text.isBlank(string(config, field, ""))) {
                missing.add(field);
            }
        }
        if (!missing.isEmpty()) {
            throw new DomainError("hr.draft.failed", "LLM 输出的角色定义缺少核心字段: " + String.join(", ", missing));
        }

        String requestedId = string(config, "role_id", "");
        String roleId = uniqueRoleId(Text.isBlank(requestedId) ? slug(requirement) : slug(requestedId));
        String name = string(config, "name", "");
        if (Text.isBlank(name)) {
            name = "新同事";
        }
        String username = string(config, "username", "");
        if (Text.isBlank(username)) {
            username = slug(roleId);
        }

        RoleSpec spec = RoleSpec.builder()
                .id(new RoleId(roleId))
                .name(name)
                .username(username)
                .uid(intValue(config, "uid", 0))
                .title(string(config, "title", ""))
                .responsibilities(string(config, "responsibilities", ""))
                .personality(string(config, "personality", ""))
                .skills(stringList(config, "skills"))
                .group(string(config, "group", "未分组"))
                .email(string(config, "email", ""))
                .promptExtra(string(config, "system_prompt_extra", ""))
                .interestKeywords(new LinkedHashSet<>(stringList(config, "interest_keywords")))
                .salienceThreshold(salience(config))
                .computer(new RoleSpec.ComputerSpec("local", Map.of()))
                .toolkits(defaultToolkits())
                .defaultRole(false)
                .build();

        logger.info("HiringService：起草角色 {}（{}），{} 项技能，{} 个关键词",
                spec.id().value(), spec.name(),
                spec.skills() == null ? 0 : spec.skills().size(),
                spec.interestKeywords() == null ? 0 : spec.interestKeywords().size());
        return spec;
    }

    @Override
    public RoleId onboard(RoleSpec spec) {
        if (spec == null) {
            throw new DomainError("hr.onboard.failed", "角色定义为空，无法上岗");
        }
        return staffing.onboard(spec).id();
    }

    // ── 提示词素材 ─────────────────────────────────────────────

    /** 现有模板摘要（role_id / title / 前 5 项技能 / 前 5 个关键词），供 LLM 对齐格式。 */
    private String templateCatalog() {
        List<Map<String, Object>> existing = new ArrayList<>();
        for (RoleSpec template : templates) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role_id", template.id().value());
            m.put("title", template.title());
            m.put("skills", firstN(template.skills(), 5));
            List<String> keywords = new ArrayList<>(template.interestKeywords() == null
                    ? List.of() : template.interestKeywords());
            keywords.sort(String::compareTo);
            m.put("keywords", firstN(keywords, 5));
            existing.add(m);
        }
        return json.writePretty(existing);
    }

    private Set<String> defaultToolkits() {
        Set<String> union = new LinkedHashSet<>();
        for (RoleSpec template : templates) {
            if (template.toolkits() != null) {
                union.addAll(template.toolkits());
            }
        }
        if (union.isEmpty()) {
            union.addAll(AppConfig.defaults().toolkits().defaults());
        }
        return union;
    }

    // ── JSON 解析 ──────────────────────────────────────────────

    /** 依次尝试：整段直接解析 → ```json 代码块 → 第一个 {…}；全部失败返回 null。 */
    Map<String, Object> parseJson(String text) {
        if (Text.isBlank(text)) {
            return null;
        }
        String trimmed = text.trim();
        Map<String, Object> direct = tryMap(trimmed);
        if (direct != null) {
            return direct;
        }
        Matcher block = JSON_BLOCK.matcher(trimmed);
        if (block.find()) {
            Map<String, Object> m = tryMap(block.group(1).trim());
            if (m != null) {
                return m;
            }
        }
        Matcher braces = JSON_BRACES.matcher(trimmed);
        if (braces.find()) {
            Map<String, Object> m = tryMap(braces.group(0));
            if (m != null) {
                return m;
            }
        }
        logger.warn("HiringService：无法从 LLM 输出解析 JSON: {}", Text.truncate(Text.squashWhitespace(text), 200));
        return null;
    }

    private Map<String, Object> tryMap(String candidate) {
        if (Text.isBlank(candidate)) {
            return null;
        }
        try {
            Map<String, Object> parsed = json.readMap(candidate);
            return parsed.isEmpty() ? null : parsed;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── 字段读取 ───────────────────────────────────────────────

    private static String string(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v).trim();
    }

    private static List<String> stringList(Map<String, Object> m, String key) {
        Object raw = m.get(key);
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String part : s.split("[,，、;；]")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    private static int intValue(Map<String, Object> m, String key, int fallback) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static double salience(Map<String, Object> config) {
        Object v = config.get("salience_threshold");
        double value = DEFAULT_SALIENCE;
        if (v instanceof Number n) {
            value = n.doubleValue();
        } else if (v instanceof String s && !s.isBlank()) {
            try {
                value = Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                value = DEFAULT_SALIENCE;
            }
        }
        return value > 0 ? value : DEFAULT_SALIENCE;
    }

    private static <T> List<T> firstN(List<T> values, int n) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.size() <= n ? new ArrayList<>(values) : new ArrayList<>(values.subList(0, n));
    }

    /** role_id / username 的归一化：只保留小写字母数字，其余折成下划线。 */
    private static String slug(String raw) {
        String s = Text.orEmpty(raw).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        if (s.isEmpty()) {
            return "new_role";
        }
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private String uniqueRoleId(String base) {
        Set<String> existing = new HashSet<>();
        for (RoleSpec template : templates) {
            existing.add(template.id().value());
        }
        String candidate = base;
        int suffix = 1;
        while (existing.contains(candidate)) {
            candidate = base + "_" + suffix;
            suffix++;
        }
        return candidate;
    }
}
