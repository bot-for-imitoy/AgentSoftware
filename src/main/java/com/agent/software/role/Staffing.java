package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.computers.Computer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抽调/移出员工（只有 COO 的工具集能调用）。
 *
 * <p>抽调：名单条目 → 新建 Role → 装配 → 启动 → 入池，电脑随之上电。
 * 移出：停 worker → Role 出池销毁 → 电脑关机、数据保留 → 名单状态置 {@link MembershipState#OUT_OF_GROUP}。
 */
public final class Staffing {

    private static final Logger logger = LoggerFactory.getLogger(Staffing.class);

    private final AgentSystem system;
    /** 防止同一员工被并发抽调两次。 */
    private final Set<String> drafting = ConcurrentHashMap.newKeySet();

    public Staffing(AgentSystem system) {
        this.system = system;
    }

    public Role draftIn(String roleId) {
        CompanyRoster roster = system.getRoster();
        RolePool pool = system.getRolePool();
        Role existing = pool.find(roleId);
        if (existing != null) {
            return existing;
        }
        Employee employee = roster.find(roleId);
        if (employee == null) {
            throw new IllegalArgumentException("no such employee: " + roleId);
        }
        if (!drafting.add(roleId)) {
            throw new IllegalStateException("employee is being drafted: " + roleId);
        }
        try {
            Role role = new Role(employee);
            employee.membership = MembershipState.IN_GROUP;
            pool.addRole(role);
            role.setup();
            role.start();
            logger.info("Staffing: {} drafted into the cohort", roleId);
            return role;
        } finally {
            drafting.remove(roleId);
        }
    }

    public void draftOut(String roleId) {
        RolePool pool = system.getRolePool();
        Role role = pool.find(roleId);
        if (role == null) {
            return;
        }
        role.stop();
        if (role.hasComputer()) {
            Computer computer = role.getComputer();
            try {
                computer.powerOff();
            } catch (Exception e) {
                logger.warn("Staffing: failed to power off computer of {}", roleId, e);
            }
            system.getComputerManager().remove(roleId);
        }
        pool.removeRole(roleId);
        Employee employee = system.getRoster().find(roleId);
        if (employee != null) {
            employee.membership = MembershipState.OUT_OF_GROUP;
        }
        logger.info("Staffing: {} left the cohort (data kept)", roleId);
    }

    public List<Employee> employees() {
        return system.getRoster().all();
    }

    public List<Role> active() {
        return system.getRolePool().all();
    }
}
