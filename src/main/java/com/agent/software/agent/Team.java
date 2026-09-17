package com.agent.software.agent;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.sim.clock.Clock;
import com.agent.software.sim.clock.Sensors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 花名册唯一所有者：只保管 {@link Agent} 集合、批量启停、只读快照。
 *
 * <p>**为什么 Team 不认识 AgentFactory**：如果把"创建 Agent"也放进 Team，
 * 装配链会形成构造环
 * {@code Team → AgentFactory → ToolkitCatalog → TalkToolkit → TeamChannel → Team}。
 * 拆分后：{@code Team} 无依赖即可先建，工具包随后注入，创建 Agent 的动作归
 * {@link Staffing}。master 的 {@code RolePool} 正是把"花名册 + 创建 + LLM 工厂 +
 * 工具装配 + worker"揉在一起，才必须靠 setter 回填。
 *
 * <p>同时实现只读视图 {@link AgentDirectory} 与时钟感知 {@link Sensors}。
 */
public final class Team implements AgentDirectory, Sensors {

    private static final Logger logger = LoggerFactory.getLogger(Team.class);

    private final java.util.Map<RoleId, Agent> byId = new java.util.LinkedHashMap<>();

    /** 只读时钟；为 null 时 {@link #allIdle()} 退化为"不看班次"的版本（单测/嵌入用）。 */
    private final Clock clock;

    public Team() {
        this(null);
    }

    /**
     * @param clock 只读时钟。{@link #allIdle()} 需要它来判断"班次是否已结束"
     *              （结束后排队等待的遗留任务不应阻塞时钟快进，对齐 master
     *              {@code AgentSystem.allRolesIdle}）。
     */
    public Team(Clock clock) {
        this.clock = clock;
    }

    /** 注册一个已装配好的角色（不启动）。 */
    public synchronized void register(Agent agent) {
        if (agent == null) {
            return;
        }
        byId.put(agent.id(), agent);
    }

    /** 离职：停止 worker 并移除。 */
    public synchronized boolean resign(RoleId id) {
        Agent agent = byId.remove(id);
        if (agent == null) {
            return false;
        }
        try {
            agent.stop();
        } catch (RuntimeException e) {
            logger.warn("停止角色 {} 失败：{}", id.value(), e.getMessage());
        }
        return true;
    }

    public synchronized Optional<Agent> agent(RoleId id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized List<Agent> agents() {
        return new ArrayList<>(byId.values());
    }

    public synchronized void startAll() {
        for (Agent agent : byId.values()) {
            try {
                agent.start();
            } catch (RuntimeException e) {
                logger.error("启动角色 {} 失败", agent.id().value(), e);
            }
        }
    }

    public synchronized void stopAll() {
        for (Agent agent : byId.values()) {
            try {
                agent.stop();
            } catch (RuntimeException e) {
                logger.warn("停止角色 {} 失败", agent.id().value(), e.getMessage());
            }
        }
    }

    public synchronized List<AgentSnapshot> snapshots() {
        List<AgentSnapshot> out = new ArrayList<>(byId.size());
        for (Agent agent : byId.values()) {
            out.add(agent.snapshot());
        }
        return out;
    }

    // ── AgentDirectory ─────────────────────────────────────────

    @Override
    public Optional<RoleSpec> spec(RoleId id) {
        return agent(id).map(Agent::spec);
    }

    @Override
    public List<RoleSpec> specs() {
        List<RoleSpec> out = new ArrayList<>();
        for (Agent agent : agents()) {
            out.add(agent.spec());
        }
        return out;
    }

    @Override
    public Optional<AgentState> stateOf(RoleId id) {
        return agent(id).map(Agent::state);
    }

    // ── Sensors ────────────────────────────────────────────────

    @Override
    public boolean anyBusy() {
        for (Agent agent : agents()) {
            if (agent.busy()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean allIdle() {
        List<Agent> all = agents();
        if (all.isEmpty()) {
            return false;
        }
        boolean afterShiftEnd = clock != null
                && !clock.calendar().withinShift(clock.nowDay());
        for (Agent agent : all) {
            if (agent.busy()) {
                return false;
            }
            // 班次结束后，留在 OFF_DUTY 队列里的遗留任务由下一班处理，不算"还在忙"
            if (!afterShiftEnd && agent.state() != AgentState.OFF_DUTY && agent.queueDepth() > 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean allOffDuty() {
        List<Agent> all = agents();
        if (all.isEmpty()) {
            return false;
        }
        for (Agent agent : all) {
            if (agent.state() != AgentState.OFF_DUTY) {
                return false;
            }
        }
        return true;
    }

    /** 按名字（或用户名）查角色，供 talk / 邮件工具解析收件人。 */
    public Optional<Agent> byName(String personName) {
        if (personName == null || personName.isBlank()) {
            return Optional.empty();
        }
        String wanted = personName.trim();
        for (Agent agent : agents()) {
            RoleSpec spec = agent.spec();
            if (wanted.equalsIgnoreCase(spec.name()) || wanted.equalsIgnoreCase(spec.username())
                    || wanted.equalsIgnoreCase(spec.id().value())) {
                return Optional.of(agent);
            }
        }
        return Optional.empty();
    }

    /** 供 Web / 控制台按组分组展示。 */
    public Map<String, List<RoleSpec>> byGroup() {
        Map<String, List<RoleSpec>> out = new LinkedHashMap<>();
        for (RoleSpec spec : specs()) {
            out.computeIfAbsent(spec.group() == null ? "" : spec.group(), k -> new ArrayList<>()).add(spec);
        }
        return out;
    }
}
