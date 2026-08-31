package com.maf.scheduler.event;

import com.maf.scheduler.core.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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
 * 时间与事件总线 (TimeEventBus) — Python 版 time_manager.py 的 Java 对应物.
 *
 * TimeEventBus 已并入 EventBus: 既是时间源 (时钟/Tick/天), 又是事件总线
 * (3 层过滤管线 + 定时事件调度表). 时间与事件深度绑定.
 *
 * Tick 推进规则 (事件驱动, 不随真实时间流逝): Tick 只在"全部角色空闲持续
 * idle_seconds 秒"时快进跳变; 角色忙碌期间 Tick 冻结.
 */
public class TimeEventBus extends EventBus {

    private static final Logger logger = LoggerFactory.getLogger(TimeEventBus.class);

    // ── 作息关键事件 (大写统一) ─────────────────────────────────
    public static final String EVENT_SHIFT_START = "SHIFT_START";
    public static final String EVENT_SHIFT_END = "SHIFT_END";
    public static final String EVENT_TASK_DUE = "TASK_DUE";

    public static final int MINUTES_PER_TICK = 10;        // 每 Tick 10 分钟
    public static final int TICKS_PER_DAY = 144;          // 每天 144 Tick (24 小时)
    public static final int SHIFT_START_TICK = 0;         // 上班: 每天第 0 Tick
    public static final int SHIFT_END_TICK = 60;          // 下班: 每天第 60 Tick
    public static final int TASK_TICK_MIN = 0;            // 任务 Tick 范围下限
    public static final int TASK_TICK_MAX = 60;           // 任务 Tick 范围上限

    public static final double DEFAULT_CHECK_INTERVAL = 30.0;
    public static final double FAST_FORWARD_IDLE_SECONDS = 60.0;

    // ── 配置 ──────────────────────────────────────────────
    public int minutesPerTick = MINUTES_PER_TICK;
    public int shiftStartTick = SHIFT_START_TICK;
    public int shiftEndTick = SHIFT_END_TICK;
    public int ticksPerDay = TICKS_PER_DAY;
    public double checkInterval = DEFAULT_CHECK_INTERVAL;

    // ── 内部状态 ──────────────────────────────────────────
    private int tick = 0;                              // 当前绝对 Tick (显式状态, 快进时跳变)
    private Thread thread = null;
    private boolean running = false;
    private Consumer<Types.Event> eventSender = null;
    private Supplier<Instant> clock = Instant::now;    // 时间源 (保留 API, Tick 是显式状态)
    private int firedDay = 0;                          // 已触发事件的天
    private boolean firedStart = false;
    private boolean firedEnd = false;
    private int[] pendingProgress = null;              // 恢复进度 (day, tick_of_day)
    private final Map<String, ScheduledTask> tasks = new LinkedHashMap<>();

    // 快进功能: 全角色空闲时跳过等待
    private Supplier<Boolean> idleChecker = null;
    private boolean fastForward = true;
    private volatile Double idleSince = null;          // 全空闲起始墙钟时间 (epoch 秒)
    private double idleSeconds = FAST_FORWARD_IDLE_SECONDS;

    // ── 定时任务 ──────────────────────────────────────────

    /** 定时任务 (注册到 TimeEventBus, 到达指定 Tick 触发提醒事件). */
    public static final class ScheduledTask {
        public String description;
        public String ownerRole;
        public int targetTick;
        public int day;
        public String taskId;
        public Map<String, Object> payload;
        public double createdAt;
        public boolean fired;
        public String eventId = "";   // 已注册到事件调度表的事件 ID

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

        /** 计算绝对触发 Tick: (day-1)*ticks_per_day + target_tick. */
        public int absoluteFireTick(int ticksPerDay) {
            return (day - 1) * ticksPerDay + targetTick;
        }
    }

    // ── 事件注册 (时间与事件深度绑定) ─────────────────────

    /**
     * 注册事件: tick=null → 立即触发 (走发送回调); tick=整数 → 存入调度表
     * 由时间线程到期自动投递.
     */
    public String registerEvent(Types.Event event, Integer tick) {
        if (tick == null) {
            dispatch(event);
        } else {
            event.triggerTick = tick;
            tickSchedule.put(event.id, event);
            logger.info("TimeEventBus 注册定时事件: id={} type={} → tick {}",
                    event.id, event.eventType, tick);
        }
        return event.id;
    }

    /** 把事件交给下游 (事件发送回调 → EventDispatcher). */
    public void dispatch(Types.Event event) {
        if (eventSender != null) {
            try {
                eventSender.accept(event);
            } catch (Exception e) {
                logger.error("TimeEventBus 事件发送失败: {}", event.eventType, e);
            }
        } else {
            logger.debug("TimeEventBus 无事件发送回调, 事件未投递: {}", event.eventType);
        }
    }

    public void setEventSender(Consumer<Types.Event> sender) {
        this.eventSender = sender;
    }

    // ── 快进功能 (全角色空闲时跳过等待) ───────────────────

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

    /** 检查是否满足快进条件并执行跳转. */
    private void checkFastForward() {
        if (!fastForward || idleChecker == null) {
            return;
        }
        boolean allIdle;
        try {
            allIdle = Boolean.TRUE.equals(idleChecker.get());
        } catch (Exception e) {
            logger.error("快进: 空闲判定回调异常", e);
            return;
        }
        if (!allIdle) {
            idleSince = null;  // 有人忙, 重置计时
            return;
        }
        double now = System.currentTimeMillis() / 1000.0;
        if (idleSince == null) {
            idleSince = now;  // 开始计时
            logger.debug("全部角色空闲, 开始快进计时 ({}s 后跳转)", (long) idleSeconds);
            return;
        }
        if (now - idleSince < idleSeconds) {
            return;  // 还没到 idle_seconds, 继续等
        }
        // 满足条件: 跳到下一个事件 Tick
        Integer target = nextEventTick();
        int current = currentTick();
        if (target == null || target <= current) {
            idleSince = now;  // 无目标, 重新计时
            logger.debug("全部角色空闲 {}s, 但无下一个事件 Tick, 继续等待", (long) idleSeconds);
            return;
        }
        this.tick = target;
        idleSince = null;
        logger.debug("全部角色已空闲 {}s, 快进到下一个事件 Tick {} (原 {})", (long) idleSeconds, target, current);
        logger.info("⚡ 快进: 全部角色空闲 ≥{}s, 时钟跳到 Tick {} (原 {})", (long) idleSeconds, target, current);
    }

    /** 计算下一个事件触发 Tick (绝对 Tick); 没有则返回 null. */
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
        if (tod >= shiftEndTick) {
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

    /** 设置时间源 (保留 API; Tick 是显式状态, 不依赖墙钟). */
    public void setClock(Supplier<Instant> clockFn) {
        this.clock = clockFn != null ? clockFn : Instant::now;
    }

    // ── 核心方法 ──────────────────────────────────────────

    public int currentTick() {
        return tick;
    }

    public int dayNumber() {
        return tick / ticksPerDay + 1;
    }

    public int tickOfDay() {
        return tick % ticksPerDay;
    }

    /** 将 Tick 转换为相对时钟 "HH:MM". */
    public String tickToTime(int tickVal) {
        int totalMinutes = tickVal * minutesPerTick;
        totalMinutes %= 24 * 60;
        int h = totalMinutes / 60;
        int m = totalMinutes % 60;
        return String.format("%02d:%02d", h, m);
    }

    public boolean isWorkingHours() {
        int tod = tickOfDay();
        return shiftStartTick <= tod && tod < shiftEndTick;
    }

    public int ticksUntilShiftEnd() {
        return Math.max(0, shiftEndTick - tickOfDay());
    }

    /** 获取今日某个 Tick 位置对应的作息事件 (null = 普通时间). */
    public String getShiftEvent(int tickOfDay) {
        if (tickOfDay == shiftStartTick) {
            return EVENT_SHIFT_START;
        }
        if (tickOfDay >= shiftEndTick) {
            return EVENT_SHIFT_END;
        }
        return null;
    }

    /** 当前作息状态描述. */
    public String describe() {
        int t = currentTick();
        int day = dayNumber();
        int tod = tickOfDay();
        if (tod >= shiftEndTick) {
            return String.format("第 %d 天, Tick %d (已下班 %d Ticks)", day, t, tod - shiftEndTick);
        }
        return String.format("第 %d 天, Tick %d (上班中, 距下班还有 %d Ticks)", day, t, shiftEndTick - tod);
    }

    // ── 时间线程 (独占) ───────────────────────────────────

    /**
     * 启动时间线程. 系统启动时刻记为 Tick 0 / 第 1 天; 若已 setProgress 则
     * 直接跳到恢复点 (事件标志按恢复点设置, 不重放已发生的事件).
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
        if (pendingProgress != null) {
            int day = pendingProgress[0];
            int tod = pendingProgress[1];
            pendingProgress = null;
            tick = (day - 1) * ticksPerDay + tod;
            firedDay = day;
            firedStart = true;   // 恢复点必在上班区间内 (上班已发生)
            firedEnd = tod >= shiftEndTick;
            logger.info("TimeEventBus: 已恢复上次进度 → 第 {} 天 Tick {}", day, tod);
        }
        thread = new Thread(this::tickLoop, "time-manager");
        thread.setDaemon(true);
        thread.start();
        logger.info("TimeEventBus 时间线程已启动 (启动时刻 = Tick 0 / 第 1 天, 检查间隔 {}s)", checkInterval);
    }

    /** 设置恢复进度: 下次 start() 时把 Tick 直接设为 (day, tick_of_day). */
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
        logger.info("TimeEventBus 时间线程已停止");
    }

    public boolean isRunning() {
        return running && thread != null && thread.isAlive();
    }

    // ── 定时任务管理 ───────────────────────────────────────

    /**
     * 注册一个定时任务, 到达指定 Tick 时向事件总线发送提醒事件.
     *
     * @throws IllegalArgumentException target_tick 超出 [0, 60] 范围.
     */
    public ScheduledTask scheduleTask(String description, String ownerRole, int targetTick,
                                      Integer day, Map<String, Object> payload) {
        if (targetTick < TASK_TICK_MIN || targetTick > TASK_TICK_MAX) {
            throw new IllegalArgumentException(String.format(
                    "target_tick 必须在 %d~%d 范围内, 得到 %d", TASK_TICK_MIN, TASK_TICK_MAX, targetTick));
        }
        ScheduledTask task = new ScheduledTask(description, ownerRole, targetTick,
                day != null ? day : dayNumber(),
                payload != null ? payload : new LinkedHashMap<>());
        tasks.put(task.taskId, task);
        // 只保存任务列表; 当天任务直接注册事件, 隔天任务等目标天上班时自动加载
        registerTaskEventIfToday(task);
        logger.info("TimeEventBus: 定时任务已注册 [{}] {} → tick {} (day {}), 所有者 {}",
                task.taskId, description, targetTick, task.day, ownerRole);
        return task;
    }

    /** 当天任务直接注册 TASK_DUE 事件到调度表 (隔天任务不注册). */
    private boolean registerTaskEventIfToday(ScheduledTask task) {
        if (task.day > dayNumber()) {
            logger.info("TimeEventBus: 任务 [{}] 是隔天任务 (day {}), 仅保存, 目标天上班时自动加载到事件总线",
                    task.taskId, task.day);
            return false;
        }
        if (!task.eventId.isEmpty() && tickSchedule.containsKey(task.eventId)) {
            logger.warn("TimeEventBus: 任务 [{}] 已注册事件 {}, 跳过重复注册 (正常流程任务只注册一次)",
                    task.taskId, task.eventId);
            return true;
        }
        String eid = registerEvent(taskToEvent(task), task.absoluteFireTick(ticksPerDay));
        task.eventId = eid;
        return true;
    }

    /** 构造任务的 TASK_DUE 提醒事件. */
    private Types.Event taskToEvent(ScheduledTask task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", task.taskId);
        payload.put("description", task.description);
        payload.put("tick", task.targetTick);
        payload.put("day", task.day);
        payload.put("owner_role", task.ownerRole);
        payload.putAll(task.payload);  // 透传附加信息 (笔记提醒: note_title 等)
        return new Types.Event("task", EVENT_TASK_DUE, Types.Priority.NORMAL, payload, task.ownerRole);
    }

    /** 目标天上班 (SHIFT_START) 时: 把到期任务加载到事件调度表. */
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
            logger.info("TimeEventBus: 上班加载 {} 个到期任务到事件总线", loaded);
        }
    }

    /** 从事件调度表取消某任务对应的事件 (任务被删除/编辑时). */
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

    /** 列出未触发的定时任务 (ownerRole 非 null 时只列该角色的), 按触发顺序排序. */
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
     * 编辑已有定时任务; 不存在返回 null.
     *
     * @throws IllegalArgumentException target_tick 超出范围, 或改到已过去的时间.
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
                    "target_tick 必须在 %d~%d 范围内, 得到 %d", TASK_TICK_MIN, TASK_TICK_MAX, newTick));
        }
        if ((newDay - 1) * ticksPerDay + newTick < currentTick()) {
            throw new IllegalArgumentException(String.format(
                    "不能把任务 [ID=%s] 改到过去的时间: 第 %d 天 Tick %d 已过期 (当前绝对 Tick %d)",
                    taskId, newDay, newTick, currentTick()));
        }
        cancelTaskEvent(taskId);
        if (description != null) {
            task.description = description;
        }
        task.targetTick = newTick;
        task.day = newDay;
        registerTaskEventIfToday(task);
        logger.info("TimeEventBus: 定时任务已编辑 [{}] → tick {} (day {})", taskId, task.targetTick, task.day);
        return task;
    }

    /** 删除定时任务 (同时取消已注册的事件). 返回是否删除成功. */
    public boolean cancelTask(String taskId) {
        if (tasks.containsKey(taskId)) {
            tasks.remove(taskId);
            cancelTaskEvent(taskId);
            logger.info("TimeEventBus: 定时任务已删除 [{}]", taskId);
            return true;
        }
        return false;
    }

    // ── 时间线程主循环 ─────────────────────────────────────

    private void tickLoop() {
        logger.debug("TimeEventBus 线程循环开始");
        while (running) {
            try {
                checkAndFire();
                // 调度表中的定时事件 (register_event(tick=N)) 到期投递
                for (Types.Event ev : checkDueEvents(currentTick())) {
                    logger.info("TimeEventBus 定时事件到期投递: id={} type={}", ev.id, ev.eventType);
                    // 任务提醒触发后标记 fired, 防止隔天 SHIFT_START 重新注册重复触发
                    if (EVENT_TASK_DUE.equals(ev.eventType)) {
                        Object tid = ev.payload.get("task_id");
                        ScheduledTask task = tid != null ? tasks.get(String.valueOf(tid)) : null;
                        if (task != null) {
                            task.fired = true;
                        }
                    }
                    dispatch(ev);
                }
                // 快进: 全部角色空闲时跳到下一个事件 Tick
                checkFastForward();
            } catch (Exception e) {
                logger.error("TimeEventBus 检查异常", e);
            }
            try {
                Thread.sleep((long) (checkInterval * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        logger.debug("TimeEventBus 线程循环结束");
    }

    /** 检查当前 Tick 并触发对应事件 (按天重置, 每天各触发一次). */
    private void checkAndFire() {
        int day = dayNumber();
        int tod = tickOfDay();

        // 新的一天 → 重置当天的事件标志
        if (day != firedDay) {
            firedDay = day;
            firedStart = false;
            firedEnd = false;
            logger.info("TimeEventBus: 进入第 {} 天", day);
        }

        // 上班事件 (每天触发一次; 条件用区间而非严格 ==, 避免错过 tick 0 窗口)
        if (!firedStart && shiftStartTick <= tod && tod < shiftEndTick) {
            firedStart = true;
            loadTodayTasksToBus();
            fireEvent(EVENT_SHIFT_START);
        }

        // 下班事件 (每天达到 shift_end_tick 后触发一次)
        if (!firedEnd && tod >= shiftEndTick) {
            firedEnd = true;
            fireEvent(EVENT_SHIFT_END);
        }
    }

    /** 构造并发送作息事件到事件总线. */
    private void fireEvent(String eventType) {
        int t = currentTick();
        int day = dayNumber();
        int tod = tickOfDay();

        String instruction;
        if (EVENT_SHIFT_END.equals(eventType)) {
            instruction = "下班时间到: 请调用 summary 工具总结今天的工作, "
                    + "总结完成后你将自动进入 OFF_DUTY 状态.";
        } else {
            instruction = "上班时间到: 查看昨日总结, 开始今天的工作.";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tick", t);
        payload.put("day", day);
        payload.put("time", tickToTime(tod));
        payload.put("shift", eventType);
        payload.put("instruction", instruction);

        Types.Event event = new Types.Event("time", eventType, Types.Priority.EMERGENCY, payload, null);
        logger.info("TimeEventBus 触发事件: {} (day={}, tick={}, time={}, priority={})",
                eventType, day, t, payload.get("time"), event.priority);
        dispatch(event);
    }

    // ── 测试辅助 (同包测试用, 等价于 Python 测试直接改 _tick / 调私有方法) ──

    /** 直接跳到指定绝对 Tick (等价于快进跳变). */
    void debugSetTick(int tick) {
        this.tick = tick;
    }

    /** 计算下一个事件触发 Tick (测试用). */
    Integer debugNextEventTick() {
        return nextEventTick();
    }

    /** 目标天上班时把到期任务加载到事件调度表 (测试用). */
    void debugLoadTodayTasksToBus() {
        loadTodayTasksToBus();
    }

    /** 注册任务事件 (测试用). */
    boolean debugRegisterTaskEventIfToday(ScheduledTask task) {
        return registerTaskEventIfToday(task);
    }

    // ── 进程级默认共享时钟 ─────────────────────────────────

    private static final AtomicReference<TimeEventBus> DEFAULT_BUS = new AtomicReference<>();

    /** 进程级默认 TimeEventBus (惰性创建). */
    public static TimeEventBus getDefaultBus() {
        return DEFAULT_BUS.updateAndGet(existing -> existing != null ? existing : new TimeEventBus());
    }
}
