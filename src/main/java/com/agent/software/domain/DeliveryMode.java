package com.agent.software.domain;

/** How a dispatched task reaches its role. */
public enum DeliveryMode {
    /** Queue now; the worker will pick it up. */
    IMMEDIATE,
    /** Hold until the role leaves the waiting state. */
    DEFER_UNTIL_IDLE,
    /** Hold until the next shift start. */
    HOLD_UNTIL_SHIFT
}
