package com.agent.software.store;

import com.agent.software.role.CompanyRoster;
import com.agent.software.role.Employee;
import com.agent.software.role.MembershipState;
import com.agent.software.utils.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 角色模板读取：顶层键 {@code role_templates}，形状 {@code {"CEO": {...}, ...}}。
 *
 * <p>不负责持久化，所以即使其它 store 暂缓，这个也必须可用，否则启动时没有角色。
 */
public class RoleTemplateStore {

    public static final String SECTION = "role_templates";

    private final JsonStore store;

    public RoleTemplateStore(JsonStore store) {
        this.store = store;
    }

    public List<Employee> employees() {
        List<Employee> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : store.section(SECTION).entrySet()) {
            out.add(toEmployee(e.getKey(), e.getValue()));
        }
        return out;
    }

    public Employee find(String roleId) {
        if (roleId == null) {
            return null;
        }
        Map<String, Object> raw = store.section(SECTION).get(roleId);
        return raw == null ? null : toEmployee(roleId, raw);
    }

    /** 默认大组 = 管理组。 */
    public List<Employee> defaultCohort() {
        List<Employee> out = new ArrayList<>();
        for (Employee e : employees()) {
            if (CompanyRoster.MANAGEMENT_GROUP.equals(e.group)) {
                out.add(e);
            }
        }
        return out;
    }

    private static Employee toEmployee(String roleId, Map<String, Object> raw) {
        Employee e = new Employee(roleId, str(raw.get("name")), str(raw.get("group")));
        e.membership = MembershipState.OUT_OF_GROUP;
        for (Map.Entry<String, Object> field : raw.entrySet()) {
            Object v = field.getValue();
            // 标量存原串（避免带上 JSON 引号），数组/对象存 JSON 文本
            e.template.put(field.getKey(), v instanceof String s ? s : Json.stringify(v));
        }
        return e;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
