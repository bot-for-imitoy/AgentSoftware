package com.agent.software.agent;

/**
 * 角色生命周期状态。
 *
 * <p>"某个状态下能否接普通工作"的规则内聚在这里，替代 master 中散落在
 * {@code EventDispatcher} / {@code AgentRole.evaluateEvent} / {@code RolePool.roleLoop}
 * 三处的状态判断。
 */
public enum AgentState {

    /** 下班：上下文已冲刷，普通工作排队等待下一班。 */
    OFF_DUTY,

    /** 在岗空闲：等待事件。 */
    ON_DUTY_IDLE,

    /** 在岗忙碌：正在执行任务。 */
    ON_DUTY_BUSY,

    /** 收尾中：班次结束、总结未完成。 */
    WRAPPING_UP,

    /** 同步等待同事回复（talk wait=true）。 */
    WAITING;

    /** 是否在岗（非 OFF_DUTY）。 */
    public boolean onDuty() {
        return this != OFF_DUTY;
    }

    /** 普通（非 EMERGENCY）事件是否应暂存而不是立即执行。 */
    public boolean holdsOrdinaryWork() {
        return this == OFF_DUTY || this == WRAPPING_UP || this == WAITING;
    }

    /** EMERGENCY 是否永远穿透。 */
    public boolean acceptsEmergency() {
        return true;
    }

    /** 由名字还原（大小写不敏感，兼容 master 的 WAIT 旧名）；未知回退 ON_DUTY_IDLE。 */
    public static AgentState parse(String name) {
        if (name != null) {
            String n = name.trim().toUpperCase(java.util.Locale.ROOT);
            if (n.equals("WAIT")) {
                return WAITING;
            }
            for (AgentState s : values()) {
                if (s.name().equals(n)) {
                    return s;
                }
            }
        }
        return ON_DUTY_IDLE;
    }
}
