package com.agent.software.model;

import com.agent.software.kernel.Ids.RoleId;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一个角色的不可变定义（人设 + 配置）。
 *
 * <p>这是 master {@code AgentRole} 中"非运行时"的那一半：只有身份、人设、事件过滤参数、
 * 电脑规格与工具包选择；不含队列、线程、状态机、工具实例与任何 I/O。
 * 运行时对象是 {@code engine.Agent}。
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

    /** 个人电脑的创建参数（kind: podman | local | ssh）。 */
    public record ComputerSpec(String kind, Map<String, String> options) {
    }

    /** 是否属于某个组；talk 的组内限制、System Prompt 的邮件规则都依赖它。 */
    public boolean hasGroup() {
        throw new UnsupportedOperationException("skeleton");
    }

    public static Builder builder() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 16 字段的防御式构造器；同时是模板 JSON / 快照 JSON 的装配点。 */
    public static final class Builder {
        public Builder id(RoleId v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder name(String v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder username(String v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder uid(int v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder title(String v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder responsibilities(String v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder personality(String v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder skills(List<String> v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder group(String v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder email(String v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder promptExtra(String v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder interestKeywords(Set<String> v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder salienceThreshold(double v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder computer(ComputerSpec v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder toolkits(Set<String> v) { throw new UnsupportedOperationException("skeleton"); }
        public Builder defaultRole(boolean v) { throw new UnsupportedOperationException("skeleton"); }
        public RoleSpec build() { throw new UnsupportedOperationException("skeleton"); }
    }
}
