package com.agent.software.company;

import com.agent.software.agent.Agent;
import com.agent.software.agent.AgentState;
import com.agent.software.agent.LifecycleGate;
import com.agent.software.agent.Team;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Tick;
import com.agent.software.sim.clock.Clock;
import com.agent.software.sim.clock.ScheduleTable;
import com.agent.software.sim.clock.TickObserver;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.EventSink;
import com.agent.software.sim.event.Priority;
import com.agent.software.transcript.Transcript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 班次反应唯一处。
 *
 * <p>对应 master {@code AgentSystem.onTimeEvent} + {@code allRolesIdle} +
 * {@code dayRolloverReady} + {@code forceWrapUp} 这一堆散落逻辑：
 * <ul>
 *   <li>SHIFT_START：全员上岗、开机、提升暂存任务、装载当天日程；</li>
 *   <li>SHIFT_END：解阻塞等待中的角色、要求各自写总结（WRAPPING_UP）；</li>
 *   <li>forceWrapUp：收尾超时兜底，把仍未下班的角色强制 OFF_DUTY。</li>
 * </ul>
 *
 * <p>它只认识时间与花名册：{@code sim} 不需要依赖 {@code agent}，
 * 因为班次反应是注册进来的观察者。
 */
public final class ShiftDirector implements TickObserver {

    private static final Logger logger = LoggerFactory.getLogger(ShiftDirector.class);

    /** 下班后给等待中的角色的合成回复。 */
    private static final String SHIFT_END_SYNTHETIC_REPLY =
            "[System: the shift ended and the colleague you were waiting for has gone off duty. "
                    + "Treat this as their reply for now, finish up your current task, "
                    + "then call the summary tool to wrap up today's work.]";

    /** 收尾超时兜底时给等待中的角色的合成回复。 */
    private static final String WRAP_UP_SYNTHETIC_REPLY =
            "[System: the daily wrap-up deadline passed — treat this as the reply for now, "
                    + "finish up and rest; leftover work resumes at the next 08:00 shift.]";

    private final Team team;
    private final Clock clock;
    private final LifecycleGate gate;
    private final ScheduleTable schedule;
    private final EventSink sink;
    private final Transcript transcript;

    /** 上一次观察到的天数（0 = 还没观察过）。 */
    private int lastDay;
    private int lastTickOfDay = -1;

    public ShiftDirector(Team team, Clock clock, LifecycleGate gate) {
        this(team, clock, gate, null, null, null);
    }

    /**
     * @param schedule   当天日程表（上班时 {@link ScheduleTable#activateDay(int)}）
     * @param sink       事件入口（上班 / 下班事件由这里广播给全员）
     * @param transcript 轨迹（可选）
     */
    public ShiftDirector(Team team, Clock clock, LifecycleGate gate, ScheduleTable schedule,
                         EventSink sink, Transcript transcript) {
        this.team = team;
        this.clock = clock;
        this.gate = gate;
        this.schedule = schedule;
        this.sink = sink;
        this.transcript = transcript;
    }

    /** 时钟每步调用；内部只在班次边界动作。 */
    @Override
    public void onTick(Tick now) {
        var dayTick = clock.nowDay();
        int shiftEndTick = clock.calendar().shiftEndTick();

        if (dayTick.day() != lastDay) {
            lastDay = dayTick.day();
            lastTickOfDay = dayTick.tickOfDay();
            onShiftStart();
            return;
        }
        if (lastTickOfDay < shiftEndTick && dayTick.tickOfDay() >= shiftEndTick) {
            onShiftEnd();
        }
        lastTickOfDay = dayTick.tickOfDay();
    }

    public void onShiftStart() {
        int day = clock.nowDay().day();
        for (Agent agent : team.agents()) {
            // 上一班没来得及收尾的角色：新班次开始前强制下班（对齐 master forceWrapUp）
            if (lastDay > 1 && agent.state() != AgentState.OFF_DUTY && agent.state() != AgentState.WAITING) {
                forceOffDuty(agent, "上一班未收尾，新班次开始时强制下班");
            }
            try {
                agent.stateMachine().to(AgentState.ON_DUTY_IDLE);
            } catch (RuntimeException e) {
                logger.debug("[{}] 上班状态迁移被拒绝：{}", agent.id().value(), e.getMessage());
            }
            try {
                if (agent.shell() != null) {
                    agent.shell().powerOn();
                }
            } catch (RuntimeException e) {
                logger.warn("[{}] 开机失败：{}", agent.id().value(), e.getMessage());
            }
            agent.promoteDeferred();
        }
        if (schedule != null) {
            schedule.activateDay(day);
        }
        system("上班：第 " + day + " 天 " + clock.currentDateTime());
        publish(EventKind.SHIFT_START, day);
    }

    public void onShiftEnd() {
        for (Agent agent : team.agents()) {
            if (agent.waits().waiting()) {
                agent.waits().abort(SHIFT_END_SYNTHETIC_REPLY);
            }
        }
        // 先广播 SHIFT_END（此时角色还在岗，任务会被正常投递），再把他们标成"收尾中"，
        // 这样收尾期间新到的普通事件只会暂存。
        system("下班：第 " + clock.nowDay().day() + " 天 " + clock.currentDateTime() + "，各角色写总结后休息");
        publish(EventKind.SHIFT_END, clock.nowDay().day());
        for (Agent agent : team.agents()) {
            AgentState state = agent.state();
            if (state == AgentState.ON_DUTY_IDLE || state == AgentState.ON_DUTY_BUSY) {
                try {
                    agent.stateMachine().to(AgentState.WRAPPING_UP);
                } catch (RuntimeException e) {
                    logger.debug("[{}] 收尾状态迁移被拒绝：{}", agent.id().value(), e.getMessage());
                }
            }
        }
    }

    public void forceWrapUp() {
        logger.warn("收尾宽限期已过，强制剩余角色下班");
        for (Agent agent : team.agents()) {
            if (agent.waits().waiting()) {
                agent.waits().abort(WRAP_UP_SYNTHETIC_REPLY);
            }
        }
        for (Agent agent : team.agents()) {
            if (agent.state() != AgentState.OFF_DUTY && !agent.busy()) {
                forceOffDuty(agent, "收尾超时，强制下班（可能没写总结）");
            }
        }
    }

    /** 全局暂停状态（Web/控制台展示用）。 */
    public boolean paused() {
        return gate != null && gate.paused();
    }

    private void forceOffDuty(Agent agent, String why) {
        try {
            agent.stateMachine().toOffDuty();
            logger.info("[{}] {}", agent.id().value(), why);
        } catch (RuntimeException e) {
            logger.debug("[{}] 强制下班失败：{}", agent.id().value(), e.getMessage());
        }
    }

    private void publish(EventKind kind, int day) {
        if (sink == null) {
            return;
        }
        try {
            sink.publish(AgentEvent.broadcast(kind, Priority.NORMAL, Payload.of("day", day)));
        } catch (RuntimeException e) {
            logger.error("广播 {} 失败", kind.wire(), e);
        }
    }

    private void system(String text) {
        if (transcript != null) {
            try {
                transcript.system(text);
            } catch (RuntimeException e) {
                logger.debug("写系统轨迹失败：{}", e.getMessage());
            }
        }
        logger.info(text);
    }
}
