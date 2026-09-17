package com.agent.software.agent.task;

/** 任务状态。 */
public enum TaskStatus {

    PENDING,
    RUNNING,
    DONE,
    FAILED;

    /** 是否已结束（DONE / FAILED）。 */
    public boolean terminal() {
        throw new UnsupportedOperationException("skeleton");
    }
}
