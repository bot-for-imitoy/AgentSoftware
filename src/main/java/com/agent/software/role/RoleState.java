package com.agent.software.role;

/**
 * 运行时状态。注意没有 OFF_DUTY / PAUSED：
 * 下班后角色仍然是 IDLE（是否派活由 {@code EventBus} 的下班暂存控制）。
 */
public enum RoleState {
    IDLE,
    BUSY,
    WAIT
}
