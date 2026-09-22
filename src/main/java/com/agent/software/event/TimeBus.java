package com.agent.software.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 模拟时钟：唯一时间源。
 *
 * <p>语义：
 * <ul>
 *   <li>1 tick = 1 模拟秒（{@link #setSecondsPerTick(double)} 可调）。</li>
 *   <li>一个 sim 日 = 86400 tick；班次 = tickOfDay 在 [0, 36000)，即 08:00–18:00。</li>
 *   <li>忙的时候按真实时间推进（每 tick 睡 tickMillis）；全员空闲时直接快进到
 *       {@link #setNextStopProvider(Supplier)} 给出的下一个事件，没有事件就跳到班次边界。</li>
 * </ul>
 *
 * <p>快进目标的来源（报备项 B3）：{@code AgentSystem} 通过
 * {@link #setNextStopProvider(Supplier)} 接上 {@code EventBus.nextDue()}；
 * 否则空闲期间排的定时事件会被推迟到边界才触发。
 */
public class TimeBus {

    private static final Logger logger = LoggerFactory.getLogger(TimeBus.class);
    private static final long TICKS_PER_DAY = 86_400L;
    private static final long SHIFT_START_TICK = 0L;
    private static final long SHIFT_END_TICK = 36_000L;
    private static final long SHIFT_START_SECONDS = 8L * 3600L;

    private volatile long now = 0L;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private volatile LocalDate baseDate = LocalDate.now();
    private volatile double secondsPerTick = 1.0;

    private final List<Consumer<TimeBus>> listeners = new CopyOnWriteArrayList<>();
    private volatile Supplier<Boolean> idleChecker;
    private volatile Supplier<Boolean> busyChecker;
    private volatile Supplier<Long> nextStopProvider;
    private volatile Runnable rolloverHook;
    private Thread thread;

    public TimeBus() {
    }

    public TimeBus(LocalDate baseDate) {
        if (baseDate != null) {
            this.baseDate = baseDate;
        }
    }

    // ── 生命周期 ────────────────────────────────────────────────

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofVirtual().name("time-bus").start(this::loop);
        logger.info("TimeBus started at {}", currentDateTime());
    }

    public synchronized void stop() {
        running = false;
        thread = null;
    }

    public boolean isRunning() {
        return running;
    }

    // ── 时间 ────────────────────────────────────────────────────

    public long now() {
        return now;
    }

    public void setNow(long tick) {
        advanceTo(tick);
    }

    public void advanceTo(long tick) {
        boolean dayChanged;
        synchronized (this) {
            if (tick <= now) {
                return;
            }
            long oldDay = now / TICKS_PER_DAY;
            now = tick;
            dayChanged = (now / TICKS_PER_DAY) != oldDay;
        }
        if (dayChanged && rolloverHook != null) {
            try {
                rolloverHook.run();
            } catch (Exception e) {
                logger.warn("TimeBus rollover hook failed", e);
            }
        }
        notifyListeners();
    }

    public void advance(long ticks) {
        if (ticks > 0) {
            advanceTo(now() + ticks);
        }
    }

    public long ticksPerDay() {
        return TICKS_PER_DAY;
    }

    public LocalDate currentDate() {
        return baseDate.plusDays(now() / TICKS_PER_DAY);
    }

    public int getDay() {
        return (int) (now() / TICKS_PER_DAY) + 1;
    }

    public long getTickOfDay() {
        return Math.floorMod(now(), TICKS_PER_DAY);
    }

    public long getShiftStartTick() {
        return SHIFT_START_TICK;
    }

    public long getShiftEndTick() {
        return SHIFT_END_TICK;
    }

    public String currentTime() {
        long secs = SHIFT_START_SECONDS + getTickOfDay();
        return LocalTime.MIDNIGHT.plusSeconds(Math.floorMod(secs, 86_400L)).toString();
    }

    public String currentDateTime() {
        return currentDate() + " " + currentTime();
    }

    public boolean isWorkingHours() {
        long tod = getTickOfDay();
        return tod >= SHIFT_START_TICK && tod < SHIFT_END_TICK;
    }

    // ── 暂停 ────────────────────────────────────────────────────

    public void pause() {
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    // ── 观察者 ──────────────────────────────────────────────────

    public void addTickListener(Consumer<TimeBus> l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public void removeTickListener(Consumer<TimeBus> l) {
        listeners.remove(l);
    }

    // ── 由 AgentSystem 注入的内部钩子（跨包，故为 public）──────────

    public void setIdleChecker(Supplier<Boolean> checker) {
        this.idleChecker = checker;
    }

    public void setBusyChecker(Supplier<Boolean> checker) {
        this.busyChecker = checker;
    }

    /** 下一个值得跳过去的 tick（通常是下一个到期事件）；返回 null 表示没有。 */
    public void setNextStopProvider(Supplier<Long> provider) {
        this.nextStopProvider = provider;
    }

    /** 时钟跨天时回调。 */
    public void setRolloverHook(Runnable hook) {
        this.rolloverHook = hook;
    }

    public void setSecondsPerTick(double secondsPerTick) {
        if (secondsPerTick > 0) {
            this.secondsPerTick = secondsPerTick;
        }
    }

    // ── 驱动循环 ────────────────────────────────────────────────

    private void loop() {
        while (running) {
            try {
                Thread.sleep(tickMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!running) {
                return;
            }
            if (paused) {
                continue;
            }
            try {
                if (isIdle()) {
                    long target = nextStop();
                    if (target > now()) {
                        advanceTo(target);
                        continue;
                    }
                }
                advance(1);
            } catch (Exception e) {
                logger.warn("TimeBus tick failed", e);
            }
        }
    }

    private boolean isIdle() {
        Supplier<Boolean> idle = idleChecker;
        if (idle != null) {
            return Boolean.TRUE.equals(idle.get());
        }
        Supplier<Boolean> busy = busyChecker;
        return busy != null && !Boolean.TRUE.equals(busy.get());
    }

    /** 空闲时跳到下一个"有意思"的 tick：有事件就跳事件，否则跳班次边界。 */
    private long nextStop() {
        long candidate = Long.MAX_VALUE;
        Supplier<Long> provider = nextStopProvider;
        if (provider != null) {
            try {
                Long n = provider.get();
                if (n != null && n > now()) {
                    candidate = Math.min(candidate, n);
                }
            } catch (Exception e) {
                logger.warn("TimeBus next-stop provider failed", e);
            }
        }
        long tod = getTickOfDay();
        long dayStart = now() - tod;
        long boundary = tod < SHIFT_END_TICK ? dayStart + SHIFT_END_TICK : dayStart + TICKS_PER_DAY;
        candidate = Math.min(candidate, boundary);
        return candidate;
    }

    private long tickMillis() {
        return Math.max(1L, (long) (secondsPerTick * 1000.0));
    }

    private void notifyListeners() {
        for (Consumer<TimeBus> l : listeners) {
            try {
                l.accept(this);
            } catch (Exception e) {
                logger.warn("TimeBus listener failed", e);
            }
        }
    }
}
