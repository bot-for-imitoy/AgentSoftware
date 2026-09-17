package com.agent.software.sim.clock;

import com.agent.software.sim.event.EventSink;

import java.util.Optional;
import com.agent.software.kernel.Tick;

/**
 * 时钟线程的唯一所有者。
 *
 * <p>一轮循环：采集 {@link Sensors} 事实 → 问 {@link ClockPolicy} → 应用
 * {@code ClockAction} → 通知 {@link TickObserver}（班次反应）→ 投递到期事件 → 睡眠。master 的 {@code TimeEventBus.tickLoop}
 * 把感知、决策、日历、调度、生命周期兜底全混在一个 920 行类里。
 *
 * <p>{@link #tickOnce()} 让测试可以单步驱动，无需真实等待。
 */
public final class ClockDriver {

    private final SimClock clock;
    private final ScheduleTable schedule;
    private final ClockPolicy policy;
    private final EventSink sink;
    private final Sensors sensors;
    private final TickObserver observer;
    private final ClockOptions options;

    public ClockDriver(SimClock clock, ScheduleTable schedule, ClockPolicy policy,
                       EventSink sink, Sensors sensors, TickObserver observer,
                       ClockOptions options) {
        this.clock = clock;
        this.schedule = schedule;
        this.policy = policy;
        this.sink = sink;
        this.sensors = sensors;
        this.observer = observer;
        this.options = options;
    }

    public void start() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void stop() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void pause() {
        throw new UnsupportedOperationException("skeleton");
    }

    public void resume() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean paused() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 单步推进（测试入口，也是线程体每次循环的核心）。 */
    public void tickOnce() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 供策略判断的"下一个事件触发点"（含班次结束 / 下一班次起点门控）。 */
    public Optional<Tick> nextFireTick() {
        throw new UnsupportedOperationException("skeleton");
    }

    public record ClockOptions(long busyPollMillis, long idlePollMillis, long wrapUpGraceMillis) {
    }
}
