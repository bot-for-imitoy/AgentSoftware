package com.agent.software.model;

/**
 * 事件与任务的紧急度——全系统唯一来源。
 *
 * <p>消灭 master 的三份重复模型：{@code Types.Priority}、{@code AgentRole.Urgency}、
 * {@code Task.urgency:int}。
 */
public enum Priority {

    LOW,
    NORMAL,
    HIGH,
    EMERGENCY;

    /** 数值权重，用于队列排序与显著性打分。 */
    public int weight() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 由权重反查；未知权重回退 NORMAL。 */
    public static Priority ofWeight(int weight) {
        throw new UnsupportedOperationException("skeleton");
    }
}
