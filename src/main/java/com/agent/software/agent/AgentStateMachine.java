package com.agent.software.agent;

/**
 * 角色状态的唯一所有者，只允许合法迁移。
 *
 * <p>master 的 {@code AgentState} 有六个写者（runtime、lifecycle、team、restore、
 * 以及两个注入工具的回调），且 {@code WRAPPING_UP} 从未被赋值。这里收成一个状态机。
 */
public final class AgentStateMachine {

    public AgentState state() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 迁移到新状态；非法迁移抛 {@code kernel.DomainError}。 */
    public void to(AgentState next) {
        throw new UnsupportedOperationException("skeleton");
    }

    public void toIdle() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void toOffDuty() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void toWaiting() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 读档时直接覆盖状态（不做迁移校验）。 */
    public void restore(AgentState state) {
        throw new UnsupportedOperationException("skeleton");
    }
}
