package com.agent.software.sim.clock;

import java.util.Optional;

/**
 * 时钟决策：时钟线程下一步该做什么。
 *
 * <p>把 master {@code TimeEventBus.tickLoop} 里"有人在忙就推进 / 全员空闲就快进 /
 * 收尾超时强制跨天"的判断抽成纯函数。注意这里只接收 {@code Optional<Tick> nextFireTick}
 * 而不是调度表对象，因此 policy 不依赖 engine。
 */
public interface ClockPolicy {

    ClockAction next(ClockSignals signals, ShiftCalendar calendar, Tick now,
                     Optional<Tick> nextFireTick);

    /** 决策所需事实，由 {@code sim.clock.Sensors} 采集。 */
    record ClockSignals(boolean anyBusy, boolean allIdle, boolean allOffDuty,
                        boolean wrapUpOverdue, long idleMillis) {
    }

    /** 时钟动作。 */
    sealed interface ClockAction {

        /** 有人在干活：按真实耗时推进相应模拟秒数。 */
        record Advance(double simulatedSeconds) implements ClockAction {
        }

        /** 全员空闲足够久：直接跳到下一个事件触发点。 */
        record FastForwardTo(Tick target) implements ClockAction {
        }

        /** 无事可做：停在原地。 */
        record Hold() implements ClockAction {
        }

        /** 收尾超时：触发强制下班兜底。 */
        record ForceWrapUp() implements ClockAction {
        }
    }
}
