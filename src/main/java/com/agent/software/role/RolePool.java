package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.utils.UUIDObjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前任务大组：被 COO 抽调进来、正在参与本次任务的角色。
 *
 * <p>不是"所有员工"——全部员工在 {@link CompanyRoster} 里，没进组的人没有 Role 实例。
 * 本类只负责注册/查找/批量启停；装配与启动是 {@link Role} 自己的事。
 */
public final class RolePool extends UUIDObjectManager<Role> {

    private static final Logger logger = LoggerFactory.getLogger(RolePool.class);

    private AgentSystem agentSystem;
    /** 容器内 uid 分配序号：1100 + 入组顺序（对齐 master RolePool.addRole）。 */
    private int uidCounter = 0;

    public RolePool() {
    }

    public RolePool(AgentSystem system) {
        this.agentSystem = system;
    }

    void bind(AgentSystem system) {
        this.agentSystem = system;
    }

    /** 只注册并绑定系统；装配/启动由 {@link Staffing} 或调用方显式调用。 */
    public void addRole(Role r) {
        if (r == null) {
            return;
        }
        if (find(r.roleId) != null) {
            throw new IllegalStateException("role already in cohort: " + r.roleId);
        }
        // 容器内 uid：模板没给就按入组顺序分配，保证同一员工的云盘/主目录归属稳定
        if (r.uid < 1100) {
            r.uid = 1100 + (++uidCounter);
        }
        r.bind(agentSystem);
        add(r);
        logger.info("RolePool: {} joined the cohort (uid={})", r.roleId, r.uid);
    }

    public boolean removeRole(String roleId) {
        Role r = find(roleId);
        return r != null && remove(r);
    }

    public Role find(String roleId) {
        if (roleId == null) {
            return null;
        }
        for (Role r : all()) {
            if (roleId.equals(r.roleId)) {
                return r;
            }
        }
        return null;
    }

    public Role findByName(String name) {
        if (name == null) {
            return null;
        }
        for (Role r : all()) {
            if (name.equals(r.name)) {
                return r;
            }
        }
        return null;
    }

    public List<Role> byGroup(String group) {
        List<Role> out = new ArrayList<>();
        for (Role r : all()) {
            if (group != null && group.equals(r.group)) {
                out.add(r);
            }
        }
        return out;
    }

    public void start() {
        for (Role r : all()) {
            r.start();
        }
    }

    public void stop() {
        for (Role r : all()) {
            r.stop();
        }
    }

    /** 管理组判定：talk 豁免部门限制、且只有管理组成员有客户端工具的依据。 */
    public boolean isManagement(Role r) {
        return r != null && CompanyRoster.MANAGEMENT_GROUP.equals(r.group);
    }
}
