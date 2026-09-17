package com.agent.software.sim.clock;

import java.util.Optional;
import com.agent.software.kernel.Tick;
import com.agent.software.sim.clock.ClockPolicy.ClockAction;
import com.agent.software.sim.clock.ClockPolicy.ClockSignals;

/**
 * 默认时钟策略。
 *
 * <p>优先级：收尾超时兜底 > 忙碌推进 > 全员空闲快进 > 原地等待。
 * 快进目标只取"下一个事件触发点"；班次结束后的下一个班次起点是否可达，
 * 由调用方在提供 {@code nextFireTick} 时决定（对齐 master 的 dayRolloverReady 门）。
 *
 * <p>纯函数：只读 final 配置字段，不读挂钟、不持状态，同样输入必得同样输出。
 */
public final class DefaultClockPolicy implements ClockPolicy {

    /** 默认"全员空闲多久才允许快进"的毫秒数。 */
    public static final long FAST_FORWARD_IDLE_SECONDS = 60L;
    /** 默认忙碌推进速度：1 真实秒 = 1 模拟秒。 */
    public static final double DEFAULT_SIM_SECONDS_PER_REAL_SECOND = 1.0;
    /** 忙碌时的轮询周期（毫秒）；也用作 3 参构造器的默认值。 */
    public static final long BUSY_POLL_MILLIS = 250L;

    private final long fastForwardIdleMillis;
    private final double simSecondsPerRealSecond;
    private final long busyPollMillis;

    public DefaultClockPolicy(long fastForwardIdleMillis) {
        this(fastForwardIdleMillis, DEFAULT_SIM_SECONDS_PER_REAL_SECOND, BUSY_POLL_MILLIS);
    }

    /**
     * @param fastForwardIdleMillis    全员空闲持续多久后才允许快进
     * @param simSecondsPerRealSecond  忙碌时每个真实秒推进多少模拟秒
     * @param busyPollMillis           忙碌时的轮询周期，用于把"这一轮的真实耗时"折算成模拟秒
     */
    public DefaultClockPolicy(long fastForwardIdleMillis, double simSecondsPerRealSecond, long busyPollMillis) {
        this.fastForwardIdleMillis = fastForwardIdleMillis;
        this.simSecondsPerRealSecond = simSecondsPerRealSecond;
        this.busyPollMillis = busyPollMillis;
    }

    @Override
    public ClockAction next(ClockSignals signals, ShiftCalendar calendar, Tick now,
                            Optional<Tick> nextFireTick) {
        // 1. 收尾超时兜底：最高优先级，保证日循环永远不会卡死。
        if (signals.wrapUpOverdue()) {
            return new ClockAction.ForceWrapUp();
        }
        // 2. 有人在忙：按"这一轮真实睡眠时长 × 速度"推进模拟时钟。
        if (signals.anyBusy()) {
            return new ClockAction.Advance(busyPollMillis / 1000.0 * simSecondsPerRealSecond);
        }
        // 3. 全员空闲足够久：直接跳到下一个触发点（必须是未来的点，否则原地等待）。
        if (!signals.anyBusy()
                && signals.allIdle()
                && signals.idleMillis() >= fastForwardIdleMillis
                && nextFireTick != null
                && nextFireTick.isPresent()
                && nextFireTick.get().after(now)) {
            return new ClockAction.FastForwardTo(nextFireTick.get());
        }
        // 4. 无事可做：停在原地（等下一次传感器变化）。
        return new ClockAction.Hold();
    }
}
