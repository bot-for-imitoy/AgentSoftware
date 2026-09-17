package com.agent.software.sim.clock;

import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventSink;
import com.agent.software.sim.clock.ClockPolicy.ClockAction;
import com.agent.software.sim.clock.ClockPolicy.ClockSignals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.BooleanSupplier;
import com.agent.software.kernel.Tick;
import com.agent.software.kernel.DayTick;

/**
 * 时钟线程的唯一所有者。
 *
 * <p>一轮循环：采集 {@link Sensors} 事实 → 问 {@link ClockPolicy} → 应用
 * {@code ClockAction} → 通知 {@link TickObserver}（班次反应）→ 投递到期事件 → 睡眠。master 的 {@code TimeEventBus.tickLoop}
 * 把感知、决策、日历、调度、生命周期兜底全混在一个 920 行类里。
 *
 * <p>{@link #tickOnce()} 让测试可以单步驱动，无需真实等待。
 *
 * <p>线程模型：{@link #tickOnce()} 由唯一的时钟线程调用，并用 {@code synchronized} 串行化
 * 自身的计时状态；{@link #pause()}/{@link #resume()}/{@link #paused()} 只用 volatile 标志，
 * 不持锁等待。虚拟线程天然是 daemon，主线程退出不会把它留下。
 */
public final class ClockDriver {

    private static final Logger logger = LoggerFactory.getLogger(ClockDriver.class);

    /** 忙碌时的轮询周期（毫秒），与 {@link DefaultClockPolicy} 的默认值保持一致。 */
    public static final long BUSY_POLL_MILLIS = DefaultClockPolicy.BUSY_POLL_MILLIS;
    /** 空闲时的轮询周期（秒）：master 的 DEFAULT_CHECK_INTERVAL。 */
    public static final double DEFAULT_CHECK_INTERVAL = 30.0;
    /** 收尾兜底的宽限期（秒）：master 的 DEFAULT_WRAP_UP_GRACE_SECONDS。 */
    public static final double DEFAULT_WRAP_UP_GRACE_SECONDS = 600.0;
    /** 空闲轮询周期换算成毫秒。 */
    public static final long DEFAULT_IDLE_POLL_MILLIS = (long) (DEFAULT_CHECK_INTERVAL * 1000.0);
    /** 收尾宽限期换算成毫秒。 */
    public static final long DEFAULT_WRAP_UP_GRACE_MILLIS = (long) (DEFAULT_WRAP_UP_GRACE_SECONDS * 1000.0);
    /** 睡眠分片粒度：让 pause()/resume()/stop() 最多这么久就能被感知。 */
    private static final long WAKE_HOP_MILLIS = 100L;
    /** stop() 等待线程结束的上限。 */
    private static final long STOP_JOIN_MILLIS = 3000L;

    private final SimClock clock;
    private final ScheduleTable schedule;
    private final ClockPolicy policy;
    private final EventSink sink;
    private final Sensors sensors;
    private final TickObserver observer;
    private final ClockOptions options;

    // ── 线程生命周期（volatile：读方不阻塞，写方不长时间持锁） ──────────
    private volatile boolean running;
    private volatile boolean paused;
    private volatile Thread thread;
    /** 上一轮采集到的 anyBusy，用于决定线程睡眠周期，避免重复调用 Sensors。 */
    private volatile boolean lastAnyBusy;

    // ── 由 ClockDriver 自己维护的计时（不放 Sensors，见 Sensors 的注释） ──
    /** 连续空闲的起点（毫秒）；<0 表示当前不空闲。 */
    private long idleSinceMillis = -1L;
    /** 连续空闲了多久（毫秒）。 */
    private long idleMillis;
    /** "第一次观察到离开班次时段"的起点（毫秒）；<0 表示当前无需收尾计时。 */
    private long offShiftSinceMillis = -1L;
    /** 收尾是否已超时（离开班次且未全员 OFF_DUTY 超过宽限期）。 */
    private boolean wrapUpOverdue;

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
        // 一致性兜底：ScheduleTable 的 DayTick→Tick 换算必须与时钟共用同一份日历，
        // 否则 bootstrap 用无参构造 ScheduleTable 时会按默认 1 秒/tick 算错触发时刻。
        if (!schedule.calendar().equals(clock.calendar())) {
            logger.warn("ScheduleTable 日历与时钟不一致，已按时钟对齐：{} → {}",
                    schedule.calendar(), clock.calendar());
            schedule.rebindCalendar(clock.calendar());
        }
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        paused = false;   // 新一轮启动不应继承上一次遗留的暂停
        Thread t = Thread.ofVirtual().name("sim-clock").unstarted(this::loop);
        t.setDaemon(true); // 虚拟线程本就是 daemon，这里显式声明意图（对虚拟线程是 no-op）
        thread = t;
        t.start();
        logger.info("sim-clock 时钟线程已启动（虚拟线程，busyPoll={}ms idlePoll={}ms wrapUpGrace={}ms）",
                options.busyPollMillis(), options.idlePollMillis(), options.wrapUpGraceMillis());
    }

    /** 停止并 join；可重复调用。 */
    public void stop() {
        Thread t;
        synchronized (this) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t == null) {
            return;
        }
        t.interrupt(); // 打断正在进行的睡眠，尽快退出
        try {
            t.join(STOP_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        logger.info("sim-clock 时钟线程已停止");
    }

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean paused() {
        return paused;
    }

    /** 单步推进（测试入口，也是线程体每次循环的核心）。 */
    public synchronized void tickOnce() {
        Tick now = clock.now();
        // 1. 先通知观察者：班次边界动作（上岗 / 收尾）必须在时钟推进之前看到边界 tick。
        try {
            observer.onTick(now);
        } catch (Exception e) {
            logger.error("sim-clock 观察者 onTick 失败 tick={}", now.value(), e);
        }

        // 2. 采集事实；任一传感器失败都按 false 处理，单点故障不能拖垮时钟。
        boolean anyBusy = readSensor("anyBusy", sensors::anyBusy);
        boolean allIdle = readSensor("allIdle", sensors::allIdle);
        boolean allOffDuty = readSensor("allOffDuty", sensors::allOffDuty);
        lastAnyBusy = anyBusy;

        // 3. 自己计时：连续空闲时长 + 收尾超时。
        long nowMillis = System.currentTimeMillis();
        if (allIdle) {
            if (idleSinceMillis < 0L) {
                idleSinceMillis = nowMillis;
            }
            idleMillis = nowMillis - idleSinceMillis;
        } else {
            idleSinceMillis = -1L;
            idleMillis = 0L;
        }

        ShiftCalendar calendar = clock.calendar();
        boolean offShift = !calendar.withinShift(clock.nowDay());
        if (offShift && !allOffDuty) {
            if (offShiftSinceMillis < 0L) {
                offShiftSinceMillis = nowMillis;   // 刚离开班次，开始宽限计时
                wrapUpOverdue = false;
            } else {
                wrapUpOverdue = (nowMillis - offShiftSinceMillis) >= options.wrapUpGraceMillis();
            }
        } else {
            offShiftSinceMillis = -1L;   // 回到班次内 / 已全员下班：计时清零
            wrapUpOverdue = false;
        }

        // 4. 决策。策略是纯函数；万一实现抛错，退化为 Hold 而不是杀掉时钟线程。
        ClockAction action;
        try {
            action = policy.next(new ClockSignals(anyBusy, allIdle, allOffDuty, wrapUpOverdue, idleMillis),
                    calendar, now, nextFireTick(allOffDuty));
        } catch (Exception e) {
            logger.error("sim-clock 策略决策失败，本轮退化为 Hold", e);
            action = new ClockAction.Hold();
        }

        // 5. 应用动作。
        apply(action);

        // 6. 投递到期事件：单个事件失败不影响后续。
        try {
            for (AgentEvent event : schedule.due(clock.now())) {
                try {
                    sink.publish(event);
                } catch (Exception e) {
                    logger.error("sim-clock 到期事件投递失败 id={} kind={}",
                            event.id().value(), event.kind().wire(), e);
                }
            }
        } catch (Exception e) {
            logger.error("sim-clock 取到期事件失败", e);
        }
    }

    /**
     * 供策略判断的"下一个事件触发点"（含班次结束 / 下一班次起点门控）。
     *
     * <p>候选 = 调度表下一个生效触发点 ∪ 当天 18:00 ∪（离开班次且全员 OFF_DUTY 时的）次日 08:00，
     * 取其中最早且严格晚于 now 的一个。
     */
    public Optional<Tick> nextFireTick() {
        return nextFireTick(readSensor("allOffDuty", sensors::allOffDuty));
    }

    /** 用本轮已采集的 allOffDuty 计算候选，避免同一 tick 内重复读传感器导致前后不一致。 */
    private Optional<Tick> nextFireTick(boolean allOffDuty) {
        Tick now = clock.now();
        ShiftCalendar calendar = clock.calendar();
        DayTick today = calendar.locate(now);

        // 调度表：其 nextFireTick 允许 == now，这里统一收紧为严格未来。
        Tick best = schedule.nextFireTick(now).filter(fire -> fire != null && fire.after(now)).orElse(null);

        // 当天班次结束也是快进目标。master 的 nextEventTick 总会加入这一项；少了它，
        // "全员空闲且没有提醒"时时钟会停在原地，永远走不到 18:00 的下班边界。
        Tick shiftEnd = calendar.at(today.day(), calendar.shiftEndTick());
        if (shiftEnd.after(now) && (best == null || shiftEnd.before(best))) {
            best = shiftEnd;
        }

        // 跨天门控（master 的 dayRolloverReady）：只有离开班次、且全员 OFF_DUTY，
        // 才允许把下一个班次起点作为候选；否则时钟在 18:00 等待收尾。
        if (!calendar.withinShift(today) && allOffDuty) {
            Tick nextStart = calendar.nextShiftStart(today);
            if (nextStart.after(now) && (best == null || nextStart.before(best))) {
                best = nextStart;
            }
        }
        return Optional.ofNullable(best);
    }

    // ── 线程体与睡眠 ─────────────────────────────────────────────

    private void loop() {
        logger.debug("sim-clock 循环开始");
        while (running) {
            if (paused) {
                sleepInterruptibly(options.idlePollMillis(), true);
                continue;
            }
            try {
                tickOnce();
            } catch (Throwable t) {
                logger.error("sim-clock 单步推进失败", t);
            }
            sleepInterruptibly(lastAnyBusy ? options.busyPollMillis() : options.idlePollMillis(), false);
        }
        logger.debug("sim-clock 循环结束");
    }

    /**
     * 分片睡眠，保证 pause()/resume()/stop() 能提前把线程唤醒。
     *
     * @param whilePaused true 表示调用方正处于暂停状态（恢复时提前返回），
     *                    false 表示运行中（暂停时提前返回）
     */
    private void sleepInterruptibly(long millis, boolean whilePaused) {
        long deadline = System.currentTimeMillis() + Math.max(0L, millis);
        while (running) {
            // 暂停/恢复状态一旦翻转就提前醒来，回到主循环重新评估。
            if (whilePaused != paused) {
                return;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                return;
            }
            try {
                Thread.sleep(Math.min(remaining, WAKE_HOP_MILLIS));
            } catch (InterruptedException e) {
                if (!running) {
                    // 停止路径：恢复中断位并退出。
                    Thread.currentThread().interrupt();
                    return;
                }
                // 仍在运行：中断只是 pause()/resume()/stop() 的唤醒信号，
                // 清掉中断位后回到主循环，避免带着中断位导致后续 sleep 立即抛异常空转。
                Thread.interrupted();
                return;
            }
        }
    }

    // ── 动作应用 ────────────────────────────────────────────────

    private void apply(ClockAction action) {
        switch (action) {
            case ClockAction.Advance advance -> {
                // 真实睡眠时长已被策略折算成模拟秒；这里换算成 tick 并至少推进 1 个，
                // 保证忙碌时时钟不会因为 round 到 0 而完全停住。
                double secondsPerTick = clock.calendar().secondsPerTick();
                long ticks = Math.max(1L, Math.round(advance.simulatedSeconds() / secondsPerTick));
                clock.advanceTicks(ticks);
            }
            case ClockAction.FastForwardTo forward -> clock.jumpTo(forward.target());
            case ClockAction.Hold ignored -> {
                // 原地等待，不做任何事。
            }
            case ClockAction.ForceWrapUp ignored -> {
                clock.jumpTo(clock.calendar().nextShiftStart(clock.nowDay()));
                // 再通知一次：让 ShiftDirector 在新班次起点做强制收尾 + 上岗。
                try {
                    observer.onTick(clock.now());
                } catch (Exception e) {
                    logger.error("sim-clock 强制收尾后的 onTick 失败", e);
                }
            }
        }
    }

    /** 传感器读取：异常按 false 处理并记 debug 日志。 */
    private boolean readSensor(String name, BooleanSupplier probe) {
        try {
            return probe.getAsBoolean();
        } catch (Exception e) {
            logger.debug("sim-clock 传感器 {} 读取失败，按 false 处理", name, e);
            return false;
        }
    }

    public record ClockOptions(long busyPollMillis, long idlePollMillis, long wrapUpGraceMillis) {

        /** 全部取默认值（busy 250ms / idle 30s / 收尾宽限 600s）。 */
        public static ClockOptions defaults() {
            return new ClockOptions(BUSY_POLL_MILLIS, DEFAULT_IDLE_POLL_MILLIS, DEFAULT_WRAP_UP_GRACE_MILLIS);
        }

        /** 轮询周期取默认值，只覆盖收尾宽限期（常用于按配置注入 wrapUpGrace）。 */
        public static ClockOptions defaults(long wrapUpGraceMillis) {
            return new ClockOptions(BUSY_POLL_MILLIS, DEFAULT_IDLE_POLL_MILLIS, wrapUpGraceMillis);
        }
    }
}
