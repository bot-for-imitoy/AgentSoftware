package com.agent.software.sim.event;

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
        return switch (this) {
            case LOW -> 1;
            case NORMAL -> 3;
            case HIGH -> 6;
            case EMERGENCY -> 10;
        };
    }

    /** 由权重反查；未知权重回退 NORMAL。 */
    public static Priority ofWeight(int weight) {
        for (Priority p : values()) {
            if (p.weight() == weight) {
                return p;
            }
        }
        return NORMAL;
    }

    /** 由名字反查（大小写不敏感）；未知回退 NORMAL。 */
    public static Priority parse(String name) {
        if (name != null) {
            for (Priority p : values()) {
                if (p.name().equalsIgnoreCase(name.trim())) {
                    return p;
                }
            }
        }
        return NORMAL;
    }
}
