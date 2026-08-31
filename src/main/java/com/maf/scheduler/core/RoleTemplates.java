package com.maf.scheduler.core;

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
 * Role Templates — 预定义角色模板 (Python 版 role_templates.py 的 Java 对应物).
 *
 * <p>模板内容统一以 JSON 描述, 内置 54 个角色模板保存在 classpath 资源
 * {@value #DEFAULT_TEMPLATES_RESOURCE} 中, 顶层为 {@code role_id → 角色配置} 的映射.
 * 类加载时自动把 JSON 载入 {@link #TEMPLATES} 注册表 (注册表即 JSON 的唯一数据源,
 * 无需再维护一份 Java 静态定义); 也可通过 {@link #loadFromJson(String)} /
 * {@link #templatesFromJson(String)} 等方法从任意 JSON (字符串/文件) 加载角色对象.
 *
 * <p>JSON 字段约定 (与 {@link #fromJsonMap(Map)} 一一对应):
 * <pre>
 * {
 *   "role_id": "architect",                // 必填: 内部索引 (职能_序号)
 *   "name": "王建国",                       // 必填: 人名 (面向 LLM/工具的暴露身份)
 *   "title": "System Architect",            // 职位
 *   "responsibilities": "…",                // 职责
 *   "personality": "…",                     // 性格特点
 *   "skills": ["…"],                        // 技能列表
 *   "interest_keywords": ["…"],             // 事件过滤关键词 (中英文)
 *   "system_prompt_extra": "…",             // 可选: 额外系统提示
 *   "is_default": false,                    // 可选: 是否默认管理角色
 *   "group": "架构与版本组",                  // 可选: 所属分组
 *   "email": "…",                           // 可选: 显式公司邮箱
 *   "username": "…", "uid": 1100,           // 可选: 覆盖自动派生的拼音用户名/容器 uid
 *   "computer_kind": "podman",              // 可选: 个人电脑类型
 *   "computer_kwargs": {},                  // 可选: 个人电脑附加参数
 *   "state": "ON_DUTY_IDLE",                // 可选: 初始 AgentState
 *   "salience_threshold": 0.4               // 可选: 事件显著性阈值
 * }
 * </pre>
 * 支持的 JSON 根形态: ① {@code {role_id: {…}, …}} 注册表映射 (内置文件形态);
 * ② {@code [{…}, …]} 角色对象数组; ③ 单个角色对象 {@code {role_id: …, …}}.
 */
public final class RoleTemplates {

    /** classpath 上的内置角色模板 JSON 资源名. */
    public static final String DEFAULT_TEMPLATES_RESOURCE = "role_templates.json";

    private RoleTemplates() {
    }

    // ── 参数化角色工厂 (程序化注册用) ─────────────────────────

    /** 构造一个返回新 AgentRole 副本的工厂 (每次调用都返回独立实例). */
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

    /** 模板名 → 工厂函数 (每次调用返回独立副本). 类加载时从 role_templates.json 填充. */
    public static final Map<String, Supplier<AgentRole>> TEMPLATES = new LinkedHashMap<>();

    static {
        loadTemplatesFromResource();
    }

    /** 从 classpath 资源加载内置模板进 {@link #TEMPLATES}. */
    private static void loadTemplatesFromResource() {
        try (InputStream in = RoleTemplates.class.getClassLoader()
                .getResourceAsStream(DEFAULT_TEMPLATES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "找不到内置角色模板资源: " + DEFAULT_TEMPLATES_RESOURCE);
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            registerFromJson(json);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(
                    "加载角色模板资源 " + DEFAULT_TEMPLATES_RESOURCE + " 失败: " + e.getMessage());
        }
    }

    // ── JSON ↔ AgentRole ──────────────────────────────────────

    /**
     * 单个角色 JSON 对象 → AgentRole (每次调用返回独立副本).
     * 缺失的可选字段回退到 AgentRole 默认值; username/uid 未显式给出时由
     * {@code name}/{@code role_id} 自动派生 (拼音用户名 + 容器 uid).
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

    /** AgentRole → 可序列化 JSON Map (默认值字段省略, 与内置模板文件形态一致). */
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

    /** 单个角色 → 缩进 JSON 字符串. */
    public static String toJsonString(AgentRole role) {
        return Json.stringifyPretty(toJsonMap(role));
    }

    /**
     * 解析角色模板 JSON → {@code role_id → 工厂函数} 表 (不写入 {@link #TEMPLATES}).
     * 支持的根形态见类注释; 工厂每次调用返回独立 AgentRole 副本.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Supplier<AgentRole>> templatesFromJson(String jsonText)
            throws IOException {
        Map<String, Supplier<AgentRole>> out = new LinkedHashMap<>();
        Object root = Json.parse(jsonText);
        if (root instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) root;
            if (m.containsKey("role_id")) {
                // 形态 ③: 单个角色对象
                registerSingleFromJson(out, m);
            } else {
                // 形态 ①: {role_id: {…}, …} — 外层键为权威 role_id
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (e.getValue() instanceof Map) {
                        Map<String, Object> conf = new LinkedHashMap<>((Map<String, Object>) e.getValue());
                        conf.put("role_id", e.getKey());
                        registerSingleFromJson(out, conf);
                    }
                }
            }
        } else if (root instanceof List) {
            // 形态 ②: [{…}, …]
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

    /** 从 JSON 文本加载角色对象列表 (每个元素都是独立副本). */
    public static List<AgentRole> loadFromJson(String jsonText) throws IOException {
        List<AgentRole> out = new ArrayList<>();
        for (Supplier<AgentRole> fn : templatesFromJson(jsonText).values()) {
            out.add(fn.get());
        }
        return out;
    }

    /** 从 JSON 文件加载角色对象列表 (UTF-8, 每个元素都是独立副本). */
    public static List<AgentRole> loadFromJson(Path path) throws IOException {
        return loadFromJson(Json.readFile(path));
    }

    /** 把 JSON 中的模板合并进全局注册表 {@link #TEMPLATES}. 返回加载的角色数量. */
    public static int registerFromJson(String jsonText) throws IOException {
        Map<String, Supplier<AgentRole>> loaded = templatesFromJson(jsonText);
        TEMPLATES.putAll(loaded);
        return loaded.size();
    }

    /** 整个注册表 → 缩进 JSON 字符串 (可用于重新生成 role_templates.json). */
    public static String registryToJsonString() {
        Map<String, Object> root = new LinkedHashMap<>();
        for (Map.Entry<String, Supplier<AgentRole>> e : TEMPLATES.entrySet()) {
            root.put(e.getKey(), toJsonMap(e.getValue().get()));
        }
        return Json.stringifyPretty(root);
    }

    // ── 默认团队 ──────────────────────────────────────────────

    /** 默认团队: 管理层 (CEO/COO/HR) + 工程团队. CFO 保留在 TEMPLATES, 不列入默认集合. */
    public static final Set<String> DEFAULT_ROLES = new LinkedHashSet<>(Arrays.asList(
            "CEO", "COO", "HR",
            "CTO",
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
            "王建国", "李明", "张伟", "刘洋", "赵强", "陈静", "孙晓", "周梅",
            "吴鑫", "郑丽", "钱峰", "冯涛", "蒋华", "沈芳", "韩磊", "杨雪",
            "朱勇", "秦风", "许亮", "何颖", "吕刚", "施慧", "魏然", "苏杰");

    private static final Set<String> usedNames = new LinkedHashSet<>();
    private static boolean namePoolInitialized = false;

    /** 从名字池获取下一个可用人名 (池耗尽时生成 员工NNN). */
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
        String name = "员工" + String.format("%03d", usedNames.size() + 1);
        usedNames.add(name);
        return name;
    }

    // ── 工厂 ──────────────────────────────────────────────────

    /** 创建每个角色模板的一个实例. */
    public static List<AgentRole> createAllRoles() {
        List<AgentRole> out = new ArrayList<>();
        for (Supplier<AgentRole> fn : TEMPLATES.values()) {
            out.add(fn.get());
        }
        return out;
    }

    /** 只创建默认管理角色 (CEO, COO, HR, CFO). */
    public static List<AgentRole> createDefaultRoles() {
        List<AgentRole> out = new ArrayList<>();
        for (String r : DEFAULT_ROLES) {
            if (TEMPLATES.containsKey(r)) {
                out.add(TEMPLATES.get(r).get());
            }
        }
        return out;
    }

    /** 按模板名获取角色. 不存在抛 IllegalArgumentException. */
    public static AgentRole getTemplate(String name) {
        Supplier<AgentRole> fn = TEMPLATES.get(name);
        if (fn == null) {
            throw new IllegalArgumentException("Unknown template '" + name + "'. Available: " + TEMPLATES.keySet());
        }
        return fn.get();
    }

    /** 注册新角色模板 (RoleFactory 招聘用). */
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
