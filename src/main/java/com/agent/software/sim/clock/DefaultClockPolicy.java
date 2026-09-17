package com.agent.software.sim.clock;

import java.util.Optional;

/**
 * 默认时钟策略。
 *
 * <p>优先级：收尾超时兜底 > 忙碌推进 > 全员空闲快进 > 原地等待。
 * 快进目标只取"下一个事件触发点"；班次结束后的下一个班次起点是否可达，
 * 由调用方在提供 {@code nextFireTick} 时决定（对齐 master 的 dayRolloverReady 门）。
 */
public final class DefaultClockPolicy implements ClockPolicy {

    public DefaultClockPolicy(long fastForwardIdleMillis) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public ClockAction next(ClockSignals signals, ShiftCalendar calendar, Tick now,
                            Optional<Tick> nextFireTick) {
        throw new UnsupportedOperationException("skeleton");
    }
}
