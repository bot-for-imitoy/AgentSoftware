package com.agent.software.agent.role;

import com.agent.software.kernel.Ids.RoleId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个角色的不可变定义（人设 + 配置）。
 *
 * <p>这是 master {@code AgentRole} 中"非运行时"的那一半：只有身份、人设、事件过滤参数、
 * 电脑规格与工具包选择；不含队列、线程、状态机、工具实例与任何 I/O。
 * 运行时对象是 {@code agent.Agent}。
 */
public record RoleSpec(
        RoleId id,
        String name,
        String username,
        int uid,
        String title,
        String responsibilities,
        String personality,
        List<String> skills,
        String group,
        String email,
        String promptExtra,
        Set<String> interestKeywords,
        double salienceThreshold,
        ComputerSpec computer,
        Set<String> toolkits,
        boolean defaultRole) {

    /** 默认显著性阈值（对齐 master {@code AgentRole.salienceThreshold}）。 */
    public static final double DEFAULT_SALIENCE_THRESHOLD = 0.4;

    /** 默认工具包（对齐 master {@code Toolkits.DEFAULT_TOOLKITS}）。 */
    public static final Set<String> DEFAULT_TOOLKITS = Set.of(
            "memory", "note", "time", "todo", "task_view", "pc", "mcp_manager", "skill", "email");

    public RoleSpec {
        if (id == null) {
            throw new com.agent.software.kernel.DomainError("role.id.null", "角色必须提供 id");
        }
        name = name == null ? "" : name;
        username = username == null || username.isBlank() ? id.value() : username;
        title = title == null ? "" : title;
        responsibilities = responsibilities == null ? "" : responsibilities;
        personality = personality == null ? "" : personality;
        skills = skills == null ? List.of() : List.copyOf(skills);
        group = group == null ? "" : group;
        email = email == null ? "" : email;
        promptExtra = promptExtra == null ? "" : promptExtra;
        interestKeywords = interestKeywords == null ? Set.of() : Set.copyOf(interestKeywords);
        if (salienceThreshold <= 0) {
            salienceThreshold = DEFAULT_SALIENCE_THRESHOLD;
        }
        computer = computer == null ? new ComputerSpec("local", Map.of()) : computer;
        toolkits = toolkits == null ? Set.of() : Set.copyOf(toolkits);
    }

    /** 个人电脑的创建参数（kind: podman | local | ssh）。 */
    public record ComputerSpec(String kind, Map<String, String> options) {

        public ComputerSpec {
            kind = kind == null || kind.isBlank() ? "local" : kind.trim().toLowerCase(java.util.Locale.ROOT);
            options = options == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }

        /** 读取一个选项，缺失时返回 fallback。 */
        public String option(String key, String fallback) {
            String v = options.get(key);
            return v == null || v.isBlank() ? fallback : v;
        }
    }

    /** 是否属于某个组；talk 的组内限制、System Prompt 的邮件规则都依赖它。 */
    public boolean hasGroup() {
        return group != null && !group.isBlank();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 由另一个定义派生一个新定义（HR 招聘时以模板为底）。 */
    public Builder toBuilder() {
        return new Builder()
                .id(id).name(name).username(username).uid(uid).title(title)
                .responsibilities(responsibilities).personality(personality).skills(skills)
                .group(group).email(email).promptExtra(promptExtra)
                .interestKeywords(interestKeywords).salienceThreshold(salienceThreshold)
                .computer(computer).toolkits(toolkits).defaultRole(defaultRole);
    }

    /** 16 字段的防御式构造器；同时是模板 JSON / 快照 JSON 的装配点。 */
    public static final class Builder {

        private RoleId id;
        private String name = "";
        private String username = "";
        private int uid;
        private String title = "";
        private String responsibilities = "";
        private String personality = "";
        private List<String> skills = new ArrayList<>();
        private String group = "";
        private String email = "";
        private String promptExtra = "";
        private Set<String> interestKeywords = new LinkedHashSet<>();
        private double salienceThreshold = DEFAULT_SALIENCE_THRESHOLD;
        private ComputerSpec computer = new ComputerSpec("local", Map.of());
        private Set<String> toolkits = new LinkedHashSet<>(DEFAULT_TOOLKITS);
        private boolean defaultRole;

        public Builder id(RoleId v) { this.id = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder username(String v) { this.username = v; return this; }
        public Builder uid(int v) { this.uid = v; return this; }
        public Builder title(String v) { this.title = v; return this; }
        public Builder responsibilities(String v) { this.responsibilities = v; return this; }
        public Builder personality(String v) { this.personality = v; return this; }
        public Builder skills(List<String> v) { this.skills = v; return this; }
        public Builder group(String v) { this.group = v; return this; }
        public Builder email(String v) { this.email = v; return this; }
        public Builder promptExtra(String v) { this.promptExtra = v; return this; }
        public Builder interestKeywords(Set<String> v) { this.interestKeywords = v; return this; }
        public Builder salienceThreshold(double v) { this.salienceThreshold = v; return this; }
        public Builder computer(ComputerSpec v) { this.computer = v; return this; }
        public Builder toolkits(Set<String> v) { this.toolkits = v; return this; }
        public Builder defaultRole(boolean v) { this.defaultRole = v; return this; }

        /** 追加一个工具包 id。 */
        public Builder addToolkit(String v) {
            if (v != null && !v.isBlank()) {
                this.toolkits.add(v);
            }
            return this;
        }

        public RoleSpec build() {
            if (id == null) {
                throw new com.agent.software.kernel.DomainError("role.id.null", "构建角色时必须提供 id");
            }
            return new RoleSpec(id, name, username, uid, title, responsibilities, personality,
                    new ArrayList<>(skills), group, email, promptExtra,
                    new LinkedHashSet<>(interestKeywords), salienceThreshold,
                    computer, new LinkedHashSet<>(toolkits), defaultRole);
        }
    }
}
