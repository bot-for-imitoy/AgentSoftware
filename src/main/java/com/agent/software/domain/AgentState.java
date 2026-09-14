package com.agent.software.domain;

/** Lifecycle state of a role worker. */
public enum AgentState {
    /** Off duty: non-emergency work is held until the next shift. */
    OFF_DUTY,
    /** On duty and available for work. */
    IDLE,
    /** On duty and executing a task. */
    BUSY,
    /** Finishing the current task before the shift ends. */
    WRAPPING_UP,
    /** Blocked synchronously waiting for a talk reply / client reply. */
    WAITING
}
