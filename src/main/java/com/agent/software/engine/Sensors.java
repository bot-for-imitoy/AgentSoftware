package com.agent.software.engine;

/**
 * ClockDriver 观察 roster 的窄接口。
 *
 * <p>存在的理由：master 的时钟反向调用 {@code AgentSystem.allRolesIdle()} /
 * {@code dayRolloverReady()}，导致时钟依赖整个系统。这里改为读一个三方法的只读视图。
 * "空闲了多久""收尾是否超时"由 {@link ClockDriver} 自己计时，不放进本接口。
 */
public interface Sensors {

    /** 是否至少有一个角色在忙。 */
    boolean anyBusy();

    /** 是否全员空闲。 */
    boolean allIdle();

    /** 是否全员 OFF_DUTY（可以跨天）。 */
    boolean allOffDuty();
}
