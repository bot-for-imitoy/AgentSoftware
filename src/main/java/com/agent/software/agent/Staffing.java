package com.agent.software.agent;

import com.agent.software.kernel.Ids.RoleId;

import java.util.List;
import com.agent.software.agent.role.RoleSpec;

/**
 * 人员进出动作（"创建 Agent 并登记"这件事的唯一所有者）。
 *
 * <p>它与 {@link Team} 分开，正是为了打断构造环：Team 先于工具包存在，
 * {@link AgentFactory} 由 bootstrap 在工具包就绪后注入。
 */
public final class Staffing {

    private final Team team;
    private final AgentFactory factory;

    public Staffing(Team team, AgentFactory factory) {
        this.team = team;
        this.factory = factory;
    }

    /** 创建并登记（不启动）；批量启动交给 {@link Team#startAll()}。 */
    public Agent hire(RoleSpec spec) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 动态入职：创建 + 登记 + 立即启动（HR 招聘用）。 */
    public Agent onboard(RoleSpec spec) {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean resign(RoleId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 从快照恢复人员与队列/历史（返回恢复数量）。 */
    public int restore(List<RoleSnapshot> roles) {
        throw new UnsupportedOperationException("skeleton");
    }
}
