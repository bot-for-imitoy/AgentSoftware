package com.agent.software.agent;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Ids.RoleId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 人员进出动作（"创建 Agent 并登记"这件事的唯一所有者）。
 *
 * <p>它与 {@link Team} 分开，正是为了打断构造环：Team 先于工具包存在，
 * {@link AgentFactory} 由 bootstrap 在工具包就绪后注入。
 */
public final class Staffing {

    private static final Logger logger = LoggerFactory.getLogger(Staffing.class);

    private final Team team;
    private final AgentFactory factory;

    public Staffing(Team team, AgentFactory factory) {
        this.team = team;
        this.factory = factory;
    }

    /** 创建并登记（不启动）；批量启动交给 {@link Team#startAll()}。 */
    public Agent hire(RoleSpec spec) {
        Agent agent = factory.create(spec);
        team.register(agent);
        logger.info("入职：{}（{}）", spec.name(), spec.id().value());
        return agent;
    }

    /** 动态入职：创建 + 登记 + 立即启动（HR 招聘用）。 */
    public Agent onboard(RoleSpec spec) {
        Agent agent = hire(spec);
        agent.start();
        return agent;
    }

    public boolean resign(RoleId id) {
        return team.resign(id);
    }

    /** 从快照恢复人员与队列/历史（返回恢复数量）。 */
    public int restore(List<RoleSnapshot> roles) {
        if (roles == null || roles.isEmpty()) {
            return 0;
        }
        int restored = 0;
        for (RoleSnapshot snapshot : roles) {
            if (snapshot == null || snapshot.spec() == null) {
                continue;
            }
            try {
                Agent agent = factory.create(snapshot.spec());
                agent.restore(snapshot);
                team.register(agent);
                restored++;
            } catch (RuntimeException e) {
                logger.error("恢复角色 {} 失败", snapshot.spec().id().value(), e);
            }
        }
        logger.info("从快照恢复 {} 个角色", restored);
        return restored;
    }
}
