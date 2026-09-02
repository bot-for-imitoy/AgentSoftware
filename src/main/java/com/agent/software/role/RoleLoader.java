package com.agent.software.role;

import com.agent.software.core.Types;
import com.agent.software.utils.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * RoleLoader — role loader: every role is loaded uniformly from the classpath
 * resource role_templates.json (the Java counterpart of the Python
 * role_templates.py; the data source is JSON rather than Java code).
 *
 * <p>All role template content is described in JSON; the 55 built-in role
 * templates live in the classpath resource
 * {@value #DEFAULT_TEMPLATES_RESOURCE}, whose top level is a
 * {@code role_id → role config} map. The JSON is loaded into the
 * {@link #TEMPLATES} registry at class load time (the registry is the only data
 * source); roles can also be loaded from arbitrary JSON (string/file) via
 * {@link #loadFromJson(String)} / {@link #templatesFromJson(String)} etc.
 *
 * <p>JSON field conventions (one-to-one with {@link #fromJsonMap(Map)}):
 * <pre>
 * {
 *   "role_id": "architect",                // required: internal index (function_index)
 *   "name": "Wang Jianguo",                // required: person name (identity exposed to LLM/tools)
 *   "title": "System Architect",            // job title
 *   "responsibilities": "…",                // responsibilities
 *   "personality": "…",                     // personality traits
 *   "skills": ["…"],                        // skill list
 *   "interest_keywords": ["…"],             // event filter keywords
 *   "system_prompt_extra": "…",             // optional: extra system prompt
 *   "is_default": false,                    // optional: default management role?
 *   "group": "Architecture & Release Group", // optional: group membership
 *   "email": "…",                           // optional: explicit company email
 *   "username": "…", "uid": 1100,           // optional: override derived pinyin username / container uid
 *   "computer_kind": "podman",              // optional: personal computer type
 *   "computer_kwargs": {},                  // optional: extra personal computer args
 *   "state": "ON_DUTY_IDLE",                // optional: initial AgentState
 *   "salience_threshold": 0.4               // optional: event salience threshold
 * }
 * </pre>
 * Supported JSON root shapes: ① {@code {role_id: {…}, …}} registry map (the
 * built-in file shape); ② {@code [{…}, …]} array of role objects; ③ a single
 * role object {@code {role_id: …, …}}.
 */
public final class RoleLoader {

    /** classpath resource name of the role template JSON (the only data source). */
    public static final String DEFAULT_TEMPLATES_RESOURCE = "role_templates.json";

    private RoleLoader() {
    }

    // ── Parameterized role factory (for programmatic registration) ─────────

    /** Builds a factory that returns a fresh AgentRole copy on each call. */
    public static Supplier<AgentRole> makeRole(String name, String roleId, String title,
                                               String responsibilities, String personality,
                                               List<String> skills, Set<String> keywords,
                                               String extra, String group) {
        return () -> AgentRole.builder()
                .name(name)
                .roleId(roleId)
                .title(title)
                .responsibilities(responsibilities)
                .personality(personality)
                .skills(new ArrayList<>(skills))
                .interestKeywords(new LinkedHashSet<>(keywords))
                .systemPromptExtra(extra)
                .group(group)
                .build();
    }

    // ── Registry ──────────────────────────────────────────────

    /** Template name → factory function (each call returns an independent copy). Populated from role_templates.json at class load time. */
    public static final Map<String, Supplier<AgentRole>> TEMPLATES = new LinkedHashMap<>();

    static {
        loadTemplatesFromResource();
    }

    /** Loads role templates from the classpath resource into {@link #TEMPLATES}. */
    private static void loadTemplatesFromResource() {
        try (InputStream in = RoleLoader.class.getClassLoader()
                .getResourceAsStream(DEFAULT_TEMPLATES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Role template resource not found: " + DEFAULT_TEMPLATES_RESOURCE);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            registerFromJson(json);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(
                    "Failed to load role template resource " + DEFAULT_TEMPLATES_RESOURCE + ": " + e.getMessage());
        }
    }

    // ── JSON ↔ AgentRole ──────────────────────────────────────

    /**
     * Single role JSON object → AgentRole (each call returns an independent copy).
     * Missing optional fields fall back to AgentRole defaults; when username/uid
     * are not given explicitly they are derived from
     * {@code name}/{@code role_id} (pinyin username + container uid).
     */
    @SuppressWarnings("unchecked")
    public static AgentRole fromJsonMap(Map<String, Object> m) {
        String roleId = Json.str(m, "role_id", "");
        AgentRole.Builder b = AgentRole.builder()
                .roleId(roleId)
                .name(Json.str(m, "name", roleId))
                .title(Json.str(m, "title", ""))
                .responsibilities(Json.str(m, "responsibilities", ""))
                .personality(Json.str(m, "personality", ""))
                .skills(Json.strList(m, "skills"))
                .interestKeywords(new LinkedHashSet<>(Json.strList(m, "interest_keywords")))
                .systemPromptExtra(Json.str(m, "system_prompt_extra", ""))
                .isDefault(Json.boolVal(m, "is_default", false))
                .group(Json.str(m, "group", ""));
        String email = Json.str(m, "email", "");
        if (!email.isEmpty()) {
            b.email(email);
        }
        String username = Json.str(m, "username", "");
        if (!username.isEmpty()) {
            b.username(username);
        }
        int uid = Json.intVal(m, "uid", 0);
        if (uid > 0) {
            b.uid(uid);
        }
        String computerKind = Json.str(m, "computer_kind", "");
        if (!computerKind.isEmpty()) {
            b.computerKind(computerKind);
        }
        Object computerKwargs = m.get("computer_kwargs");
        if (computerKwargs instanceof Map) {
            b.computerKwargs(new LinkedHashMap<>((Map<String, Object>) computerKwargs));
        }
        String state = Json.str(m, "state", "");
        if (!state.isEmpty()) {
            b.state(Types.AgentState.from(state));
        }
        double salienceThreshold = Json.doubleVal(m, "salience_threshold", 0);
        if (salienceThreshold > 0) {
            b.salienceThreshold(salienceThreshold);
        }
        return b.build();
    }

    /** AgentRole → serializable JSON Map (default-valued fields omitted, matching the template file shape). */
    public static Map<String, Object> toJsonMap(AgentRole role) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", role.name);
        m.put("role_id", role.roleId);
        if (role.username != null && !role.username.isEmpty()) {
            m.put("username", role.username);
        }
        if (role.uid > 0) {
            m.put("uid", role.uid);
        }
        m.put("title", role.title);
        m.put("responsibilities", role.responsibilities);
        m.put("personality", role.personality);
        m.put("skills", new ArrayList<>(role.skills));
        m.put("interest_keywords", new ArrayList<>(role.interestKeywords));
        if (role.systemPromptExtra != null && !role.systemPromptExtra.isEmpty()) {
            m.put("system_prompt_extra", role.systemPromptExtra);
        }
        if (role.isDefault) {
            m.put("is_default", true);
        }
        if (role.group != null && !role.group.isEmpty()) {
            m.put("group", role.group);
        }
        if (role.email != null && !role.email.isEmpty()) {
            m.put("email", role.email);
        }
        if (role.computerKind != null && !role.computerKind.isEmpty()
                && !"podman".equals(role.computerKind)) {
            m.put("computer_kind", role.computerKind);
        }
        if (!role.computerKwargs.isEmpty()) {
            m.put("computer_kwargs", new LinkedHashMap<>(role.computerKwargs));
        }
        if (role.state != Types.AgentState.ON_DUTY_IDLE) {
            m.put("state", role.state.value);
        }
        if (role.salienceThreshold != 0.4) {
            m.put("salience_threshold", role.salienceThreshold);
        }
        return m;
    }

    /** Single role → indented JSON string. */
    public static String toJsonString(AgentRole role) {
        return Json.stringifyPretty(toJsonMap(role));
    }

    /**
     * Parse role template JSON → {@code role_id → factory function} table
     * (does not write into {@link #TEMPLATES}). Root shapes are described in the
     * class comment; each factory call returns an independent AgentRole copy.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Supplier<AgentRole>> templatesFromJson(String jsonText)
            throws IOException {
        Map<String, Supplier<AgentRole>> out = new LinkedHashMap<>();
        Object root = Json.parse(jsonText);
        if (root instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) root;
            if (m.containsKey("role_id")) {
                // Shape ③: a single role object
                registerSingleFromJson(out, m);
            } else {
                // Shape ①: {role_id: {…}, …} — outer keys are the authoritative role_ids
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (e.getValue() instanceof Map) {
                        Map<String, Object> conf = new LinkedHashMap<>((Map<String, Object>) e.getValue());
                        conf.put("role_id", e.getKey());
                        registerSingleFromJson(out, conf);
                    }
                }
            }
        } else if (root instanceof List) {
            // Shape ②: [{…}, …]
            for (Object o : (List<Object>) root) {
                if (o instanceof Map) {
                    registerSingleFromJson(out, (Map<String, Object>) o);
                }
            }
        }
        return out;
    }

    private static void registerSingleFromJson(Map<String, Supplier<AgentRole>> out,
                                               Map<String, Object> conf) {
        AgentRole template = fromJsonMap(conf);
        out.put(template.roleId, () -> fromJsonMap(conf));
    }

    /** Loads a list of role objects from JSON text (each element is an independent copy). */
    public static List<AgentRole> loadFromJson(String jsonText) throws IOException {
        List<AgentRole> out = new ArrayList<>();
        for (Supplier<AgentRole> fn : templatesFromJson(jsonText).values()) {
            out.add(fn.get());
        }
        return out;
    }

    /** Loads a list of role objects from a JSON file (UTF-8, each element is an independent copy). */
    public static List<AgentRole> loadFromJson(Path path) throws IOException {
        return loadFromJson(Json.readFile(path));
    }

    /** Merges JSON templates into the global registry {@link #TEMPLATES}. Returns the number of roles loaded. */
    public static int registerFromJson(String jsonText) throws IOException {
        Map<String, Supplier<AgentRole>> loaded = templatesFromJson(jsonText);
        TEMPLATES.putAll(loaded);
        return loaded.size();
    }

    /** Whole registry → indented JSON string (can be used to regenerate role_templates.json). */
    public static String registryToJsonString() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Supplier<AgentRole>> e : TEMPLATES.entrySet()) {
            root.put(e.getKey(), toJsonMap(e.getValue().get()));
        }
        return Json.stringifyPretty(root);
    }

    // ── Default team ──────────────────────────────────────────

    /** Default team: management (CEO/COO/HR/CTO/business analyst) + engineering team. CFO stays in TEMPLATES and is not in the default set. */
    public static final Set<String> DEFAULT_ROLES = new LinkedHashSet<>(Arrays.asList(
            "CEO", "COO", "HR",
            "CTO",
            "business_analyst",
            "frontend_lead", "backend_lead", "fullstack_lead",
            "mobile_lead", "test_lead",
            "architect", "release_manager",
            "frontend_dev_1", "frontend_dev_2", "frontend_dev_3",
            "backend_dev_1", "backend_dev_2", "backend_dev_3",
            "mobile_dev_1", "mobile_dev_2", "mobile_dev_3",
            "fullstack_dev_1", "fullstack_dev_2", "fullstack_dev_3",
            "tester_1", "tester_2", "tester_3", "tester_4", "tester_5",
            "tester_6", "tester_7", "tester_8", "tester_9", "tester_10",
            "tester_11", "tester_12", "tester_13", "tester_14", "tester_15",
            "tester_16", "tester_17", "tester_18", "tester_19", "tester_20",
            "attacker_1", "attacker_2", "attacker_3"));

    // ── Name pool for auto-generating person names ─────────────

    private static final List<String> NAME_POOL = Arrays.asList(
            "Wang Jianguo", "Li Ming", "Zhang Wei", "Liu Yang", "Zhao Qiang", "Chen Jing", "Sun Xiao", "Zhou Mei",
            "Wu Xin", "Zheng Li", "Qian Feng", "Feng Tao", "Jiang Hua", "Shen Fang", "Han Lei", "Yang Xue",
            "Zhu Yong", "Qin Feng", "Xu Liang", "He Ying", "Lv Gang", "Shi Hui", "Wei Ran", "Su Jie");

    private static final Set<String> usedNames = new LinkedHashSet<>();
    private static boolean namePoolInitialized = false;

    /** Gets the next available person name from the pool (generates EmployeeNNN when the pool is exhausted). */
    public static synchronized String nextName() {
        if (!namePoolInitialized) {
            namePoolInitialized = true;
            for (Supplier<AgentRole> fn : TEMPLATES.values()) {
                usedNames.add(fn.get().name);
            }
        }
        for (String n : NAME_POOL) {
            if (!usedNames.contains(n)) {
                usedNames.add(n);
                return n;
            }
        }
        String name = "Employee " + String.format("%03d", usedNames.size() + 1);
        usedNames.add(name);
        return name;
    }

    // ── Factory ────────────────────────────────────────────────

    /** Creates one instance of every role template. */
    public static List<AgentRole> createAllRoles() {
        List<AgentRole> out = new ArrayList<>();
        for (Supplier<AgentRole> fn : TEMPLATES.values()) {
            out.add(fn.get());
        }
        return out;
    }

    /** Creates only the default management roles (CEO, COO, HR, CFO). */
    public static List<AgentRole> createDefaultRoles() {
        List<AgentRole> out = new ArrayList<>();
        for (String r : DEFAULT_ROLES) {
            if (TEMPLATES.containsKey(r)) {
                out.add(TEMPLATES.get(r).get());
            }
        }
        return out;
    }

    /** Gets a role by template name. Throws IllegalArgumentException if it does not exist. */
    public static AgentRole getTemplate(String name) {
        Supplier<AgentRole> fn = TEMPLATES.get(name);
        if (fn == null) {
            throw new IllegalArgumentException("Unknown template '" + name + "'. Available: " + TEMPLATES.keySet());
        }
        return fn.get();
    }

    /** Registers a new role template (used by RoleFactory for hiring). */
    public static void addTemplate(AgentRole role) {
        TEMPLATES.put(role.roleId, () -> AgentRole.builder()
                .name(role.name)
                .roleId(role.roleId)
                .username(role.username)
                .uid(role.uid)
                .title(role.title)
                .responsibilities(role.responsibilities)
                .personality(role.personality)
                .skills(new ArrayList<>(role.skills))
                .interestKeywords(new LinkedHashSet<>(role.interestKeywords))
                .systemPromptExtra(role.systemPromptExtra)
                .isDefault(role.isDefault)
                .group(role.group)
                .email(role.email)
                .computerKind(role.computerKind)
                .computerKwargs(new LinkedHashMap<>(role.computerKwargs))
                .salienceThreshold(role.salienceThreshold)
                .state(role.state)
                .build());
    }
}
