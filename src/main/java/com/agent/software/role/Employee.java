package com.agent.software.role;

import com.agent.software.utils.Data;
import com.agent.software.utils.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公司员工名单条目：轻量数据，不持有任何运行时对象。
 *
 * <p>被抽调进大组时，由 {@link Role} 从 {@link #template} 构建出运行时实例；
 * 没进大组的员工只有这条记录（"假死"）。
 */
public final class Employee implements Data {

    public String roleId;
    public String name;
    public String group;
    public MembershipState membership = MembershipState.OUT_OF_GROUP;
    /** 模板原始字段；值可能是 JSON 字符串（例如 skills 数组）。 */
    public Map<String, String> template = new LinkedHashMap<>();

    public Employee() {
    }

    public Employee(String roleId, String name, String group) {
        this.roleId = roleId;
        this.name = name == null ? "" : name;
        this.group = group == null ? "" : group;
    }

    public boolean inGroup() {
        return membership == MembershipState.IN_GROUP;
    }

    public String templateString(String key, String def) {
        String v = template.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    /** 模板里的数组字段（如 skills / interest_keywords）是 JSON 数组字符串。 */
    public java.util.List<String> templateList(String key) {
        String v = template.get(key);
        if (v == null || v.isBlank()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Object o : Json.parseArray(v)) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("role_id", roleId == null ? "" : roleId);
        d.put("name", name == null ? "" : name);
        d.put("group", group == null ? "" : group);
        d.put("membership", membership == null ? MembershipState.OUT_OF_GROUP.name() : membership.name());
        d.put("template", Json.stringify(template == null ? new LinkedHashMap<>() : template));
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        if (data == null) {
            return;
        }
        this.roleId = data.getOrDefault("role_id", "");
        this.name = data.getOrDefault("name", "");
        this.group = data.getOrDefault("group", "");
        this.membership = "IN_GROUP".equalsIgnoreCase(data.get("membership"))
                ? MembershipState.IN_GROUP : MembershipState.OUT_OF_GROUP;
        String t = data.get("template");
        this.template = new LinkedHashMap<>();
        if (t != null && !t.isBlank()) {
            for (Map.Entry<String, Object> e : Json.parseObject(t).entrySet()) {
                Object v = e.getValue();
                this.template.put(e.getKey(), v instanceof String s ? s : Json.stringify(v));
            }
        }
    }

    @Override
    public String toString() {
        return "Employee(" + roleId + ", " + name + ", " + group + ", " + membership + ")";
    }
}
