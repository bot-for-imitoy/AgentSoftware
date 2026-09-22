package com.agent.software.role;

/**
 * 名单层状态：员工在不在当前任务大组里。
 *
 * <p>它属于 {@link Employee}（假死员工也有），不能放进 {@link RoleState} —— 后者是 Role 的运行时字段，
 * 而假死员工根本没有 Role 实例。
 */
public enum MembershipState {
    IN_GROUP,
    OUT_OF_GROUP
}
