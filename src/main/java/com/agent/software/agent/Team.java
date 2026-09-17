package com.agent.software.agent;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.sim.clock.Sensors;

import java.util.List;
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

    private final java.util.Map<RoleId, Agent> byId = new java.util.LinkedHashMap<>();

    public Team() {
    }

    /** 注册一个已装配好的角色（不启动）。 */
    public void register(Agent agent) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 离职：停止 worker 并移除。 */
    public boolean resign(RoleId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    public Optional<Agent> agent(RoleId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    public List<Agent> agents() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void startAll() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void stopAll() {
        throw new UnsupportedOperationException("skeleton");
    }

    public List<AgentSnapshot> snapshots() {
        throw new UnsupportedOperationException("skeleton");
    }

    // ── AgentDirectory ─────────────────────────────────────────

    @Override
    public Optional<RoleSpec> spec(RoleId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<RoleSpec> specs() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Optional<AgentState> stateOf(RoleId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    // ── Sensors ────────────────────────────────────────────────

    @Override
    public boolean anyBusy() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public boolean allIdle() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public boolean allOffDuty() {
        throw new UnsupportedOperationException("skeleton");
    }
}
