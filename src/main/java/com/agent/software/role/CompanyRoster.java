package com.agent.software.role;

import com.agent.software.utils.Data;
import com.agent.software.utils.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 公司员工名单：全部员工（模板数据 + 在组状态）。
 *
 * <p>与 {@link RolePool} 的区别：名单里的员工不一定有 {@code Role} 实例。
 */
public final class CompanyRoster implements Data {

    /** 默认大组所在的分组名。 */
    public static final String MANAGEMENT_GROUP = "Leadership Group";

    private final List<Employee> employees = new CopyOnWriteArrayList<>();

    public CompanyRoster() {
    }

    public CompanyRoster(List<Employee> employees) {
        if (employees != null) {
            this.employees.addAll(employees);
        }
    }

    public void add(Employee e) {
        if (e != null) {
            this.employees.add(e);
        }
    }

    public List<Employee> all() {
        return List.copyOf(employees);
    }

    public Employee find(String roleId) {
        if (roleId == null) {
            return null;
        }
        for (Employee e : employees) {
            if (roleId.equals(e.roleId)) {
                return e;
            }
        }
        return null;
    }

    public Employee findByName(String name) {
        if (name == null) {
            return null;
        }
        for (Employee e : employees) {
            if (name.equals(e.name)) {
                return e;
            }
        }
        return null;
    }

    public List<Employee> byGroup(String group) {
        List<Employee> out = new ArrayList<>();
        for (Employee e : employees) {
            if (group != null && group.equals(e.group)) {
                out.add(e);
            }
        }
        return out;
    }

    public List<Employee> byMembership(MembershipState state) {
        List<Employee> out = new ArrayList<>();
        for (Employee e : employees) {
            if (e.membership == state) {
                out.add(e);
            }
        }
        return out;
    }

    /** 默认大组 = 管理组。 */
    public List<Employee> defaultCohort() {
        return byGroup(MANAGEMENT_GROUP);
    }

    public int size() {
        return employees.size();
    }

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        List<Map<String, String>> list = new ArrayList<>();
        for (Employee e : employees) {
            list.add(e.getData());
        }
        d.put("employees", Json.stringify(list));
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        employees.clear();
        if (data == null) {
            return;
        }
        String raw = data.get("employees");
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (Object o : Json.parseArray(raw)) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, String> record = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                record.put(String.valueOf(e.getKey()),
                        e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            Employee employee = new Employee();
            employee.loadData(record);
            employees.add(employee);
        }
    }

    @Override
    public String toString() {
        return "CompanyRoster(" + employees.size() + " employees)";
    }
}
