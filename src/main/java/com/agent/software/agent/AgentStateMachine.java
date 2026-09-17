package com.agent.software.agent;

import com.agent.software.kernel.DomainError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色状态的唯一所有者，只允许合法迁移。
 *
 * <p>master 的 {@code AgentState} 有六个写者（runtime、lifecycle、team、restore、
 * 以及两个注入工具的回调），且 {@code WRAPPING_UP} 从未被赋值。这里收成一个状态机。
 */
public final class AgentStateMachine {

    private static final Logger logger = LoggerFactory.getLogger(AgentStateMachine.class);

    /**
     * 合法迁移表。
     *
     * <p>唯一被拒绝的形状是"从 OFF_DUTY 直接进入某个在岗状态"：下班后必须先经过
     * {@code ON_DUTY_IDLE}（上班），否则队列提升、开机、日程装载这些副作用都会被跳过。
     * 其余迁移都允许——master 的强制收尾、读档、工具回调都要求能从任意状态落到
     * OFF_DUTY / WRAPPING_UP。
     */
    private static final Map<AgentState, Set<AgentState>> ALLOWED = Map.of(
            AgentState.OFF_DUTY, EnumSet.of(AgentState.ON_DUTY_IDLE),
            AgentState.ON_DUTY_IDLE, EnumSet.of(AgentState.ON_DUTY_BUSY, AgentState.WRAPPING_UP,
                    AgentState.OFF_DUTY, AgentState.WAITING),
            AgentState.ON_DUTY_BUSY, EnumSet.of(AgentState.ON_DUTY_IDLE, AgentState.WRAPPING_UP,
                    AgentState.OFF_DUTY, AgentState.WAITING),
            AgentState.WRAPPING_UP, EnumSet.of(AgentState.OFF_DUTY, AgentState.ON_DUTY_IDLE,
                    AgentState.ON_DUTY_BUSY, AgentState.WAITING),
            AgentState.WAITING, EnumSet.of(AgentState.ON_DUTY_IDLE, AgentState.ON_DUTY_BUSY,
                    AgentState.WRAPPING_UP, AgentState.OFF_DUTY));

    private volatile AgentState state = AgentState.ON_DUTY_IDLE;

    public AgentState state() {
        return state;
    }

    /** 迁移到新状态；非法迁移抛 {@code kernel.DomainError}。 */
    public synchronized void to(AgentState next) {
        if (next == null) {
            throw new DomainError("state.null", "目标状态不能为空");
        }
        AgentState current = state;
        if (current == next) {
            return;
        }
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(next)) {
            throw new DomainError("state.illegal", "非法状态迁移：" + current + " → " + next);
        }
        state = next;
        logger.debug("状态迁移 {} → {}", current, next);
    }

    public void toIdle() {
        to(AgentState.ON_DUTY_IDLE);
    }

    public void toOffDuty() {
        to(AgentState.OFF_DUTY);
    }

    public void toWaiting() {
        to(AgentState.WAITING);
    }

    /** 读档时直接覆盖状态（不做迁移校验）。 */
    public synchronized void restore(AgentState state) {
        this.state = state == null ? AgentState.ON_DUTY_IDLE : state;
    }
}
