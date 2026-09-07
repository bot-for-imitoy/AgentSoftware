package com.agent.software.event;

import com.agent.software.core.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Time and event bus (TimeEventBus) — the Java counterpart of the Python time_manager.py.
 *
 * TimeEventBus merges into EventBus: it is both a time source (simulated calendar clock / Tick /
 * day) and an event bus (3-layer filtering pipeline + timed event schedule table). Time and events
 * are deeply coupled.
 *
 * <p><b>Simulated wall clock.</b> One tick = {@link #MINUTES_PER_TICK} simulated minutes. Every
 * "work day" is a 24&nbsp;h tick cycle ({@link #TICKS_PER_DAY} ticks) that starts at 08:00 of a
 * real calendar date: tick 0 of each day is 08:00 and the shift-end tick
 * ({@link #SHIFT_END_TICK}) is 18:00. The calendar date is {@link #baseDate()} + (day&nbsp;−&nbsp;1)
 * (rolling past midnight within the cycle when needed), so entering the system always reads as
 * "today 08:00".
 *
 * <p><b>Clock advancement rules (event-driven, does not flow with real time).</b>
 * <ul>
 *   <li><i>Busy flow:</i> while at least one role is working ({@link #setBusyChecker}), the clock
 *       advances with the real time spent working at a compressed rate
 *       ({@link #simMinutesPerRealSecond}, default 1 simulated minute per real second). Busy work
 *       may never push the clock past the 18:00 shift end of the current day — anything still
 *       queued then is carried over to the next day.</li>
 *   <li><i>Idle fast-forward:</i> once <b>all</b> roles have been idle for
 *       {@code idle_seconds}, the clock jumps straight to the next scheduled event tick (a task
 *       reminder / the shift end / the next shift start) — nobody waits in real time.</li>
 *   <li><i>Day rollover:</i> after SHIFT_END every role writes its daily summary and goes OFF_DUTY;
 *       once the whole team has wrapped up ({@link #setRolloverReadyChecker}) the clock rolls over
 *       to the next calendar day's 08:00 shift start. If the wrap-up stalls longer than
 *       {@link #wrapUpGraceSeconds}, {@link #setRolloverForceHook} is invoked so the loop can
 *       never deadlock.</li>
 * </ul>
 */
public class TimeEventBus extends EventBus {

    private static final Logger logger = LoggerFactory.getLogger(TimeEventBus.class);

    // ── Work-rest key events (uniform uppercase) ─────────────────────────────────
    public static final String EVENT_SHIFT_START = "SHIFT_START";
    public static final String EVENT_SHIFT_END = "SHIFT_END";
    public static final String EVENT_TASK_DUE = "TASK_DUE";

    public static final int MINUTES_PER_TICK = 10;        // 10 minutes per tick
    public static final int TICKS_PER_DAY = 144;          // 144 ticks per day (24 hours)
    public static final int SHIFT_START_TICK = 0;         // shift start: tick 0 of each day → 08:00
    public static final int SHIFT_END_TICK = 60;          // shift end: tick 60 of each day → 18:00
    public static final int TASK_TICK_MIN = 0;            // lower bound of the task tick range
    public static final int TASK_TICK_MAX = 60;           // upper bound of the task tick range (tick 60 = 18:00)
    public static final int SHIFT_START_HOUR = 8;         // the simulated day starts at 08:00
    public static final int MINUTES_PER_DAY = 24 * 60;    // 1440 minutes in a day

    public static final double DEFAULT_CHECK_INTERVAL = 30.0;
    public static final double FAST_FORWARD_IDLE_SECONDS = 60.0;
    /** Default speed of the busy clock: 1 real second of team work = 1 simulated minute. */
    public static final double DEFAULT_SIM_MINUTES_PER_REAL_SECOND = 1.0;
    /** Real seconds the clock waits for the daily wrap-up to finish before forcing the rollover. */
    public static final double DEFAULT_WRAP_UP_GRACE_SECONDS = 600.0;
    /** Poll cadence of the time thread while at least one role is busy (for a smooth clock). */
    public static final long BUSY_POLL_MILLIS = 250;

    // ── Configuration ──────────────────────────────────────────────
    public int minutesPerTick = MINUTES_PER_TICK;
    public int shiftStartTick = SHIFT_START_TICK;
    public int shiftEndTick = SHIFT_END_TICK;
    public int ticksPerDay = TICKS_PER_DAY;
    public double checkInterval = DEFAULT_CHECK_INTERVAL;
    /** Hour of the day the simulated clock shows at tick 0 of each day (default 08:00). */
    public int shiftStartHour = SHIFT_START_HOUR;
    /** Busy-clock speed in simulated minutes per real second; 0 freezes the clock while busy (legacy). */
    public double simMinutesPerRealSecond = DEFAULT_SIM_MINUTES_PER_REAL_SECOND;
    /** Real seconds allowed for the post-shift wrap-up before {@link #rolloverForceHook} fires. */
    public double wrapUpGraceSeconds = DEFAULT_WRAP_UP_GRACE_SECONDS;

    // ── Internal state ──────────────────────────────────────────
    private int tick = 0;                              // current absolute tick (explicit state, jumps on fast-forward)
    private Thread thread = null;
    private boolean running = false;
    private Consumer<Types.Event> eventSender = null;
    private Supplier<Instant> clock = Instant::now;    // time source (API retained; tick is explicit state)
    private int firedDay = 0;                          // day on which events were fired
    private boolean firedStart = false;
    private boolean firedEnd = false;
    private int[] pendingProgress = null;              // resume progress (day, tick_of_day)
    private final Map<String, ScheduledTask> tasks = new LinkedHashMap<>();

    // Fast-forward feature: skip waiting when all roles are idle
    private Supplier<Boolean> idleChecker = null;
    private boolean fastForward = true;
    private volatile Double idleSince = null;          // wall-clock time when all roles became idle (epoch seconds)
    private double idleSeconds = FAST_FORWARD_IDLE_SECONDS;

    // Simulated wall clock (calendar-anchored at 08:00)
    private LocalDate baseDate = LocalDate.now();      // calendar date of day 1 (run start, 08:00)
    private volatile double busyAccumMinutes = 0.0;    // fractional busy-time accumulator (sub-tick remainder)
    private Supplier<Boolean> busyChecker = null;      // true while at least one role is working
    private Supplier<Boolean> rolloverReadyChecker = null; // true when the whole team has wrapped up (may roll the day)
    private Runnable rolloverForceHook = null;         // invoked when the wrap-up grace period is exceeded
    private volatile Double shiftEndedAt = null;       // wall seconds when SHIFT_END fired (wrap-up stall timer)

    // ── Scheduled tasks ──────────────────────────────────────────

    /** Scheduled task (registered with TimeEventBus; fires a reminder event when the specified tick is reached). */
    public static final class ScheduledTask {
        public String description;
        public String ownerRole;
        public int targetTick;
        public int day;
        public String taskId;
        public Map<String, Object> payload;
        public double createdAt;
        public boolean fired;
        public String eventId = "";   // ID of the event registered in the event schedule table

        public ScheduledTask(String description, String ownerRole, int targetTick,
                             int day, Map<String, Object> payload) {
            this.description = description;
            this.ownerRole = ownerRole;
            this.targetTick = targetTick;
            this.day = day;
            this.taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            this.payload = payload != null ? payload : new LinkedHashMap<>();
            this.createdAt = System.currentTimeMillis() / 1000.0;
        }

        /** Compute the absolute fire tick: (day-1)*ticks_per_day + target_tick. */
        public int absoluteFireTick(int ticksPerDay) {
            return (day - 1) * ticksPerDay + targetTick;
        }
    }

    // ── Event registration (time and events deeply coupled) ─────────────────────

    /**
     * Register an event: tick=null → fire immediately (via the send callback); tick=integer → store in the
     * schedule table and the time thread delivers it automatically when due.
     */
    public String registerEvent(Types.Event event, Integer tick) {
        if (tick == null) {
            dispatch(event);
        } else {
            event.triggerTick = tick;
            tickSchedule.put(event.id, event);
            logger.info("TimeEventBus registered timed event: id={} type={} → tick {}",
                    event.id, event.eventType, tick);
        }
        return event.id;
    }

    /** Hand the event to the downstream consumer (event send callback → EventDispatcher). */
    public void dispatch(Types.Event event) {
        if (eventSender != null) {
            try {
                eventSender.accept(event);
            } catch (Exception e) {
                logger.error("TimeEventBus event send failed: {}", event.eventType, e);
            }
        } else {
            logger.debug("TimeEventBus has no event send callback, event not delivered: {}", event.eventType);
        }
    }

    public void setEventSender(Consumer<Types.Event> sender) {
        this.eventSender = sender;
    }

    // ── Fast-forward (skip waiting when all roles are idle) ───────────────────

    public void setIdleChecker(Supplier<Boolean> checker) {
        this.idleChecker = checker;
    }

    public void setFastForward(boolean enabled, double idleSeconds) {
        this.fastForward = enabled;
        this.idleSeconds = idleSeconds;
        if (!enabled) {
            this.idleSince = null;
        }
    }

    // ── Busy clock + day rollover wiring ──────────────────────────

    /** Install the "someone is working right now" callback that drives the busy clock. */
    public void setBusyChecker(Supplier<Boolean> checker) {
        this.busyChecker = checker;
    }

    /** Install the day-rollover gate: only when it returns true may the clock jump to the next day's 08:00 shift start. */
    public void setRolloverReadyChecker(Supplier<Boolean> checker) {
        this.rolloverReadyChecker = checker;
    }

    /** Install the wrap-up force hook, invoked when the shift ended but the team has not wrapped up within {@link #wrapUpGraceSeconds}. */
    public void setRolloverForceHook(Runnable hook) {
        this.rolloverForceHook = hook;
    }

    /** Whether the next-day shift start is reachable right now (default true when no gate is installed). */
    private boolean dayRolloverReady() {
        return rolloverReadyChecker == null || Boolean.TRUE.equals(rolloverReadyChecker.get());
    }

    /** Whether at least one role is currently working (busy-clock driver). */
    private boolean isAnyRoleBusy() {
        if (busyChecker == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(busyChecker.get());
        } catch (Exception e) {
            logger.error("Busy check callback failed", e);
            return false;
        }
    }

    /** Check whether the fast-forward conditions are met and perform the jump. */
    private void checkFastForward() {
        if (!fastForward || idleChecker == null) {
            return;
        }
        boolean allIdle;
        try {
            allIdle = Boolean.TRUE.equals(idleChecker.get());
        } catch (Exception e) {
            logger.error("Fast-forward: idle check callback failed", e);
            return;
        }
        if (!allIdle) {
            idleSince = null;  // someone is busy, reset the timer
            return;
        }
        double now = System.currentTimeMillis() / 1000.0;
        if (idleSince == null) {
            idleSince = now;  // start timing
            logger.debug("All roles idle, starting fast-forward timing (jump after {}s)", (long) idleSeconds);
            return;
        }
        if (now - idleSince < idleSeconds) {
            return;  // not yet idle_seconds, keep waiting
        }
        // Condition met: jump to the next event tick
        Integer target = nextEventTick();
        int current = currentTick();
        if (target == null || target <= current) {
            idleSince = now;  // no target, restart timing
            logger.debug("All roles idle for {}s, but there is no next event tick, keep waiting", (long) idleSeconds);
            return;
        }
        this.tick = target;
        idleSince = null;
        logger.debug("All roles have been idle for {}s, fast-forwarding to next event tick {} (was {})", (long) idleSeconds, target, current);
        logger.info("⚡ Fast-forward: all roles idle ≥{}s, clock jumps to tick {} (was {})", (long) idleSeconds, target, current);
    }

    /** Compute the next event fire tick (absolute tick); returns null if none. */
    private Integer nextEventTick() {
        int now = currentTick();
        int day = dayNumber();
        List<Integer> candidates = new ArrayList<>();
        for (Types.Event ev : tickSchedule.values()) {
            if (ev.triggerTick != null) {
                candidates.add(ev.triggerTick);
            }
        }
        for (ScheduledTask task : tasks.values()) {
            candidates.add(task.absoluteFireTick(ticksPerDay));
        }
        candidates.add((day - 1) * ticksPerDay + shiftEndTick);
        int tod = tickOfDay();
        // The next shift start is only reachable after the shift has ended AND the whole team has
        // finished its daily wrap-up (all roles OFF_DUTY) — otherwise the clock waits at 18:00.
        if (tod >= shiftEndTick && dayRolloverReady()) {
            candidates.add(day * ticksPerDay + shiftStartTick);
        }
        int best = -1;
        for (int c : candidates) {
            if (c > now && (best == -1 || c < best)) {
                best = c;
            }
        }
        return best == -1 ? null : best;
    }

    /** Set the time source (API retained; tick is explicit state and does not depend on the wall clock). */
    public void setClock(Supplier<Instant> clockFn) {
        this.clock = clockFn != null ? clockFn : Instant::now;
    }

    // ── Simulated calendar clock (08:00 anchored) ──────────────

    /** Calendar date of day 1 (the day the run starts, at 08:00). */
    public LocalDate baseDate() {
        return baseDate;
    }

    public void setBaseDate(LocalDate date) {
        this.baseDate = date != null ? date : LocalDate.now();
    }

    /** Current simulated calendar date (day numbers roll at 08:00, so a late tick-of-day may show the next date). */
    public LocalDate currentDate() {
        return currentLocalDateTime().toLocalDate();
    }

    /** Current simulated date as "yyyy-MM-dd". */
    public String currentDateString() {
        return currentDate().toString();
    }

    /** Current simulated time of day as "HH:MM". */
    public String currentTime() {
        return tickToTime(tickOfDay());
    }

    /** Current simulated date-time as "yyyy-MM-dd HH:mm". */
    public String currentDateTime() {
        LocalDateTime dt = currentLocalDateTime();
        return String.format("%s %02d:%02d", dt.toLocalDate(), dt.getHour(), dt.getMinute());
    }

    /** Current simulated date-time. */
    public LocalDateTime currentLocalDateTime() {
        long minutes = (long) shiftStartHour * 60 + (long) tickOfDay() * minutesPerTick;
        long dayOffset = Math.floorDiv(minutes, MINUTES_PER_DAY);
        int minutesOfDay = (int) Math.floorMod(minutes, MINUTES_PER_DAY);
        return LocalDateTime.of(
                baseDate.plusDays((long) dayNumber() - 1 + dayOffset),
                LocalTime.of(minutesOfDay / 60, minutesOfDay % 60));
    }

    /** Wall clock shown when each day's shift starts (e.g. "08:00"). */
    public String shiftStartTime() {
        return tickToTime(shiftStartTick);
    }

    /** Wall clock shown when each day's shift ends (e.g. "18:00"). */
    public String shiftEndTime() {
        return tickToTime(shiftEndTick);
    }

    // ── Core methods ──────────────────────────────────────────

    public int currentTick() {
        return tick;
    }

    public int dayNumber() {
        return tick / ticksPerDay + 1;
    }

    public int tickOfDay() {
        return tick % ticksPerDay;
    }

    /**
     * Convert a (day-local) tick to the simulated wall clock "HH:MM". The simulated day is anchored
     * at {@link #shiftStartHour}:00, so tick 0 of each day maps to 08:00 and the shift-end tick
     * ({@code SHIFT_END_TICK} = 60) maps to 18:00.
     */
    public String tickToTime(int tickVal) {
        int totalMinutes = minutesOfDay(tickVal);
        return String.format("%02d:%02d", totalMinutes / 60, totalMinutes % 60);
    }

    /** Minutes of day (0..1439) for the given tick, anchored at the 08:00 shift start. */
    private int minutesOfDay(int tickVal) {
        long total = (long) shiftStartHour * 60 + (long) tickVal * minutesPerTick;
        return (int) Math.floorMod(total, MINUTES_PER_DAY);
    }

    public boolean isWorkingHours() {
        int tod = tickOfDay();
        return shiftStartTick <= tod && tod < shiftEndTick;
    }

    public int ticksUntilShiftEnd() {
        return Math.max(0, shiftEndTick - tickOfDay());
    }

    /** Get the work-rest event for a given tick position today (null = normal time). */
    public String getShiftEvent(int tickOfDay) {
        if (tickOfDay == shiftStartTick) {
            return EVENT_SHIFT_START;
        }
        if (tickOfDay >= shiftEndTick) {
            return EVENT_SHIFT_END;
        }
        return null;
    }

    /** Description of the current simulated time and work-rest status. */
    public String describe() {
        int day = dayNumber();
        int tod = tickOfDay();
        String clock = currentDateTime();
        if (tod >= shiftEndTick) {
            return String.format("%s Day %d (off duty — shift ended at %s; after the daily wrap-up the next shift starts tomorrow 08:00)",
                    clock, day, shiftEndTime());
        }
        return String.format("%s Day %d (on duty — shift ends at %s)",
                clock, day, shiftEndTime());
    }

    // ── Time thread (exclusive) ───────────────────────────────────

    /**
     * Start the time thread. The system startup moment is recorded as tick 0 / day 1 = 08:00 of
     * {@link #baseDate()}; if setProgress was called, jump directly to the resume point (event
     * flags are set according to the resume point; already-fired events are not replayed).
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        tick = 0;
        running = true;
        firedDay = 0;
        firedStart = false;
        firedEnd = false;
        shiftEndedAt = null;
        busyAccumMinutes = 0;
        if (pendingProgress != null) {
            int day = pendingProgress[0];
            int tod = pendingProgress[1];
            pendingProgress = null;
            tick = (day - 1) * ticksPerDay + tod;
            firedDay = day;
            firedStart = true;   // the resume point must be within the working-hours range (shift start already happened)
            firedEnd = tod >= shiftEndTick;
            logger.info("TimeEventBus: resumed previous progress → day {} tick {} ({})",
                    day, tod, currentDateTime());
        }
        thread = new Thread(this::tickLoop, "time-manager");
        thread.setDaemon(true);
        thread.start();
        logger.info("TimeEventBus time thread started (startup moment = {} 08:00 day 1, check interval {}s)",
                baseDate, checkInterval);
    }

    /** Set resume progress: on the next start(), set the tick directly to (day, tick_of_day). */
    public synchronized void setProgress(int day, int tickOfDay) {
        this.pendingProgress = new int[]{day, tickOfDay};
    }

    public synchronized void stop() {
        running = false;
        if (thread != null && thread.isAlive()) {
            try {
                thread.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        logger.info("TimeEventBus time thread stopped");
    }

    public boolean isRunning() {
        return running && thread != null && thread.isAlive();
    }

    // ── Scheduled task management ───────────────────────────────────────

    /**
     * Register a scheduled task; when the specified tick is reached, send a reminder event to the event bus.
     *
     * @throws IllegalArgumentException if target_tick is outside the [0, 60] range.
     */
    public ScheduledTask scheduleTask(String description, String ownerRole, int targetTick,
                                      Integer day, Map<String, Object> payload) {
        if (targetTick < TASK_TICK_MIN || targetTick > TASK_TICK_MAX) {
            throw new IllegalArgumentException(String.format(
                    "target_tick must be within %d~%d, got %d", TASK_TICK_MIN, TASK_TICK_MAX, targetTick));
        }
        ScheduledTask task = new ScheduledTask(description, ownerRole, targetTick,
                day != null ? day : dayNumber(),
                payload != null ? payload : new LinkedHashMap<>());
        tasks.put(task.taskId, task);
        // Only save the task list; register an event directly for same-day tasks, and load next-day tasks
        // automatically when the target day's shift starts
        registerTaskEventIfToday(task);
        logger.info("TimeEventBus: scheduled task registered [{}] {} → tick {} (day {}), owner {}",
                task.taskId, description, targetTick, task.day, ownerRole);
        return task;
    }

    /** For same-day tasks, register the TASK_DUE event directly into the schedule table (next-day tasks are not registered). */
    private boolean registerTaskEventIfToday(ScheduledTask task) {
        if (task.day > dayNumber()) {
            logger.info("TimeEventBus: task [{}] is a next-day task (day {}), only saved; it will be loaded into the event bus automatically when the target day's shift starts",
                    task.taskId, task.day);
            return false;
        }
        if (!task.eventId.isEmpty() && tickSchedule.containsKey(task.eventId)) {
            logger.warn("TimeEventBus: task [{}] already registered event {}, skipping duplicate registration (a task in the normal flow is registered only once)",
                    task.taskId, task.eventId);
            return true;
        }
        String eid = registerEvent(taskToEvent(task), task.absoluteFireTick(ticksPerDay));
        task.eventId = eid;
        return true;
    }

    /** Build the TASK_DUE reminder event for a task. */
    private Types.Event taskToEvent(ScheduledTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", task.taskId);
        payload.put("description", task.description);
        payload.put("tick", task.targetTick);
        payload.put("day", task.day);
        payload.put("owner_role", task.ownerRole);
        payload.putAll(task.payload);  // pass through extra info (note reminders: note_title, etc.)
        return new Types.Event("task", EVENT_TASK_DUE, Types.Priority.NORMAL, payload, task.ownerRole);
    }

    /** When the target day's shift starts (SHIFT_START): load due tasks into the event schedule table. */
    private void loadTodayTasksToBus() {
        int today = dayNumber();
        int loaded = 0;
        for (ScheduledTask task : new ArrayList<>(tasks.values())) {
            if (task.fired) {
                continue;
            }
            if (task.day <= today && task.eventId.isEmpty()) {
                if (registerTaskEventIfToday(task)) {
                    loaded++;
                }
            }
        }
        if (loaded > 0) {
            logger.info("TimeEventBus: loaded {} due tasks into the event bus at shift start", loaded);
        }
    }

    /** Cancel the event for a task from the event schedule table (when the task is deleted/edited). */
    private boolean cancelTaskEvent(String taskId) {
        for (Map.Entry<String, Types.Event> e : new ArrayList<>(tickSchedule.entrySet())) {
            Object tid = e.getValue().payload.get("task_id");
            if (taskId.equals(tid)) {
                tickSchedule.remove(e.getKey());
                return true;
            }
        }
        return false;
    }

    /** List unfired scheduled tasks (only those of the role when ownerRole is not null), sorted by fire order. */
    public List<ScheduledTask> listTasks(String ownerRole) {
        List<ScheduledTask> out = new ArrayList<>();
        for (ScheduledTask t : tasks.values()) {
            if (!t.fired && (ownerRole == null || ownerRole.equals(t.ownerRole))) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparingInt(t -> t.absoluteFireTick(ticksPerDay)));
        return out;
    }

    /**
     * Edit an existing scheduled task; returns null if it does not exist.
     *
     * @throws IllegalArgumentException if target_tick is out of range, or the time is moved into the past.
     */
    public ScheduledTask editTask(String taskId, String description, Integer targetTick, Integer day) {
        ScheduledTask task = tasks.get(taskId);
        if (task == null) {
            return null;
        }
        int newDay = day != null ? day : task.day;
        int newTick = targetTick != null ? targetTick : task.targetTick;
        if (newTick < TASK_TICK_MIN || newTick > TASK_TICK_MAX) {
            throw new IllegalArgumentException(String.format(
                    "target_tick must be within %d~%d, got %d", TASK_TICK_MIN, TASK_TICK_MAX, newTick));
        }
        if ((newDay - 1) * ticksPerDay + newTick < currentTick()) {
            throw new IllegalArgumentException(String.format(
                    "cannot move task [ID=%s] to a past time: day %d tick %d has already passed (current absolute tick %d)",
                    taskId, newDay, newTick, currentTick()));
        }
        cancelTaskEvent(taskId);
        if (description != null) {
            task.description = description;
        }
        task.targetTick = newTick;
        task.day = newDay;
        registerTaskEventIfToday(task);
        logger.info("TimeEventBus: scheduled task edited [{}] → tick {} (day {})", taskId, task.targetTick, task.day);
        return task;
    }

    /** Delete a scheduled task (also cancels its registered event). Returns whether deletion succeeded. */
    public boolean cancelTask(String taskId) {
        if (tasks.containsKey(taskId)) {
            tasks.remove(taskId);
            cancelTaskEvent(taskId);
            logger.info("TimeEventBus: scheduled task deleted [{}]", taskId);
            return true;
        }
        return false;
    }

    // ── Time thread main loop ─────────────────────────────────────

    private void tickLoop() {
        logger.debug("TimeEventBus thread loop started");
        long lastBusyNanos = System.nanoTime();
        boolean wasBusy = false;
        while (running) {
            long nowNanos = System.nanoTime();
            boolean busy = isAnyRoleBusy();
            try {
                if (busy) {
                    if (!wasBusy) {
                        lastBusyNanos = nowNanos;  // a fresh busy spell starts now (do not charge the idle sleep)
                    }
                    advanceBusyClock((nowNanos - lastBusyNanos) / 1_000_000_000.0);
                    lastBusyNanos = nowNanos;
                }
                wasBusy = busy;
                checkAndFire();
                // Deliver timed events in the schedule table (register_event(tick=N)) when due
                for (Types.Event ev : checkDueEvents(currentTick())) {
                    logger.info("TimeEventBus timed event delivered when due: id={} type={}", ev.id, ev.eventType);
                    // Mark fired after a task reminder triggers, preventing next-day SHIFT_START from re-registering and re-firing it
                    if (EVENT_TASK_DUE.equals(ev.eventType)) {
                        Object tid = ev.payload.get("task_id");
                        ScheduledTask task = tid != null ? tasks.get(String.valueOf(tid)) : null;
                        if (task != null) {
                            task.fired = true;
                        }
                    }
                    dispatch(ev);
                }
                // Fast-forward: jump to the next event tick when all roles are idle
                checkFastForward();
                // Wrap-up stall guard: force the day rollover if the team never finished wrapping up
                checkWrapUpForce();
            } catch (Exception e) {
                logger.error("TimeEventBus check failed", e);
            }
            try {
                Thread.sleep(busy ? BUSY_POLL_MILLIS : (long) (checkInterval * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.debug("TimeEventBus thread loop ended");
    }

    /**
     * Advance the simulated clock by the real time spent with at least one role busy
     * ({@link #simMinutesPerRealSecond} simulated minutes per real second). Busy work may never
     * push the clock past the current day's shift-end tick (18:00): work still queued when the
     * shift ends simply carries over to the next day.
     */
    private void advanceBusyClock(double realSeconds) {
        if (simMinutesPerRealSecond <= 0 || realSeconds <= 0) {
            return;
        }
        advanceBusySimMinutes(realSeconds * simMinutesPerRealSecond);
    }

    /** Fold accumulated simulated busy minutes into ticks (never past the 18:00 shift end). */
    private void advanceBusySimMinutes(double simMinutes) {
        if (simMinutes <= 0) {
            return;
        }
        if (tickOfDay() >= shiftEndTick) {
            busyAccumMinutes = 0;  // the work day is over (wrap-up runs with the clock frozen at 18:00)
            return;
        }
        busyAccumMinutes += simMinutes;
        while (busyAccumMinutes >= minutesPerTick) {
            int available = shiftEndTick - tickOfDay();
            if (available <= 0) {
                busyAccumMinutes = 0;  // reached 18:00: discard busy time beyond the shift (it belongs to tomorrow)
                break;
            }
            int step = Math.min((int) Math.floor(busyAccumMinutes / minutesPerTick), available);
            busyAccumMinutes -= (double) step * minutesPerTick;
            this.tick += step;
            logger.debug("Sim clock busy-advance: +{} tick(s) → tick {} ({})",
                    step, tick, currentTime());
        }
    }

    /**
     * Wrap-up stall guard: once the shift has ended the clock waits (frozen at 18:00) for every
     * role to write its daily summary and go OFF_DUTY. If that has not happened within
     * {@link #wrapUpGraceSeconds}, invoke {@link #rolloverForceHook} so the day loop can never
     * deadlock (e.g. a failed summary task left a role stuck on duty).
     */
    private void checkWrapUpForce() {
        if (wrapUpGraceSeconds <= 0 || rolloverForceHook == null || rolloverReadyChecker == null) {
            return;
        }
        if (!firedEnd || tickOfDay() < shiftEndTick) {
            shiftEndedAt = null;
            return;
        }
        if (dayRolloverReady()) {
            shiftEndedAt = null;
            return;
        }
        double now = System.currentTimeMillis() / 1000.0;
        if (shiftEndedAt == null) {
            shiftEndedAt = now;  // the shift ended; start the wrap-up grace timer
            return;
        }
        if (now - shiftEndedAt >= wrapUpGraceSeconds) {
            shiftEndedAt = null;
            logger.warn("TimeEventBus: daily wrap-up did not finish within {}s of shift end; forcing the rollover",
                    (long) wrapUpGraceSeconds);
            try {
                rolloverForceHook.run();
            } catch (Exception e) {
                logger.error("TimeEventBus: wrap-up force hook failed", e);
            }
        }
    }

    /** Check the current tick and fire the corresponding events (reset per day; each fires once per day). */
    private void checkAndFire() {
        int day = dayNumber();
        int tod = tickOfDay();

        // New day → reset today's event flags
        if (day != firedDay) {
            firedDay = day;
            firedStart = false;
            firedEnd = false;
            shiftEndedAt = null;
            busyAccumMinutes = 0;  // a new 08:00 shift starts with a clean busy-time budget
            logger.info("TimeEventBus: entering day {} ({} 08:00)", day, baseDate.plusDays((long) day - 1));
        }

        // Shift-start event (fires once per day; the condition uses a range rather than strict == to avoid missing the tick-0 window)
        if (!firedStart && shiftStartTick <= tod && tod < shiftEndTick) {
            firedStart = true;
            loadTodayTasksToBus();
            fireEvent(EVENT_SHIFT_START);
        }

        // Shift-end event (fires once per day after reaching shift_end_tick)
        if (!firedEnd && tod >= shiftEndTick) {
            firedEnd = true;
            shiftEndedAt = System.currentTimeMillis() / 1000.0;
            fireEvent(EVENT_SHIFT_END);
        }
    }

    /** Build and send a work-rest event to the event bus. */
    private void fireEvent(String eventType) {
        int t = currentTick();
        int day = dayNumber();
        int tod = tickOfDay();

        String instruction;
        if (EVENT_SHIFT_END.equals(eventType)) {
            instruction = "Shift end at " + shiftEndTime() + ": please call the summary tool to summarize "
                    + "today's work; after the summary is complete you will automatically enter the OFF_DUTY state.";
        } else {
            instruction = "Shift start at " + shiftStartTime() + ": review yesterday's summary and start today's work.";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tick", t);
        payload.put("day", day);
        payload.put("date", currentDateString());
        payload.put("time", currentTime());
        payload.put("datetime", currentDateTime());
        payload.put("shift", eventType);
        payload.put("instruction", instruction);

        Types.Event event = new Types.Event("time", eventType, Types.Priority.EMERGENCY, payload, null);
        logger.info("TimeEventBus fired event: {} (day={}, tick={}, time={}, priority={})",
                eventType, day, t, payload.get("time"), event.priority);
        dispatch(event);
    }

    // ── Test helpers (for same-package tests; equivalent to Python tests directly changing _tick / calling private methods) ──

    /** Jump directly to the specified absolute tick (equivalent to a fast-forward jump). */
    void debugSetTick(int tick) {
        this.tick = tick;
    }

    /** Compute the next event fire tick (for tests). */
    Integer debugNextEventTick() {
        return nextEventTick();
    }

    /** Load due tasks into the event schedule table when the target day's shift starts (for tests). */
    void debugLoadTodayTasksToBus() {
        loadTodayTasksToBus();
    }

    /** Register a task event (for tests). */
    boolean debugRegisterTaskEventIfToday(ScheduledTask task) {
        return registerTaskEventIfToday(task);
    }

    /** Advance the clock by simulated busy minutes without waiting on a wall clock (test hook). */
    void debugAdvanceBusySimMinutes(double simMinutes) {
        advanceBusySimMinutes(simMinutes);
    }

    // ── Process-level default shared clock ─────────────────────────────────

    private static final AtomicReference<TimeEventBus> DEFAULT_BUS = new AtomicReference<>();

    /** Process-level default TimeEventBus (created lazily). */
    public static TimeEventBus getDefaultBus() {
        return DEFAULT_BUS.updateAndGet(existing -> existing != null ? existing : new TimeEventBus());
    }
}
