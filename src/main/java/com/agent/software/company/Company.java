package com.agent.software.company;

import com.agent.software.agent.Agent;
import com.agent.software.agent.LifecycleGate;
import com.agent.software.agent.RoleSnapshot;
import com.agent.software.agent.Staffing;
import com.agent.software.agent.Team;
import com.agent.software.agent.dispatch.EventRouter;
import com.agent.software.agent.dialog.ConversationMemory;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.task.Task;
import com.agent.software.company.store.CompanySnapshot;
import com.agent.software.company.store.SnapshotStore;
import com.agent.software.sim.clock.ClockDriver;
import com.agent.software.sim.clock.ScheduleTable;
import com.agent.software.sim.clock.SimClock;
import com.agent.software.sim.event.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 顶层编排门面（薄）。
 *
 * <p>它只做组合与生命周期排序，不实现任何业务规则：
 * <ul>
 *   <li>{@code start()}：先起 worker，再起时钟（保证 SHIFT_START 有消费者）；</li>
 *   <li>{@code stop()}：先停时钟，再停 worker，最后落快照；</li>
 *   <li>{@code save()/restore()}：组装/应用 {@code CompanySnapshot}；</li>
 *   <li>实现 {@link CompanyView}，让 Web/控制台不依赖 engine。</li>
 * </ul>
 * master 的 {@code AgentSystem} 在同样位置混入了通知、邮件、暂停细节与数据目录。
 */
public final class Company implements CompanyView {

    private static final Logger logger = LoggerFactory.getLogger(Company.class);

    /** 快照格式版本（读档时用它判断兼容性）。 */
    public static final int SNAPSHOT_VERSION = 1;

    private final Team team;
    private final SimClock clock;
    private final ClockDriver driver;
    private final EventRouter router;
    private final ScheduleTable schedule;
    private final ShiftDirector director;
    private final LifecycleGate gate;
    private final Staffing staffing;
    private final SnapshotStore snapshots;

    public Company(Team team, SimClock clock, ClockDriver driver, EventRouter router,
                   ScheduleTable schedule, ShiftDirector director, LifecycleGate gate,
                   Staffing staffing, SnapshotStore snapshots) {
        this.team = team;
        this.clock = clock;
        this.driver = driver;
        this.router = router;
        this.schedule = schedule;
        this.director = director;
        this.gate = gate;
        this.staffing = staffing;
        this.snapshots = snapshots;
    }

    public void start() {
        // 先起 worker 再起时钟：否则 SHIFT_START 广播出来没人消费
        team.startAll();
        driver.start();
        logger.info("公司已启动：{}", status().describe());
    }

    public void stop() {
        driver.stop();
        team.stopAll();
        try {
            save();
        } catch (RuntimeException e) {
            logger.error("退出前落快照失败", e);
        }
    }

    /** 外部事件入口（客户消息、邮件通知、测试注入）。 */
    public void publish(AgentEvent event) {
        router.publish(event);
    }

    /** 落快照。 */
    public void save() {
        snapshots.save(snapshot());
        logger.info("快照已保存（{} 个角色）", team.agents().size());
    }

    /** 读快照；返回恢复的角色数。 */
    public int restore() {
        return snapshots.load().map(snapshot -> {
            if (snapshot.version() != SNAPSHOT_VERSION) {
                logger.warn("快照版本 {} 与当前版本 {} 不一致，跳过读档",
                        snapshot.version(), SNAPSHOT_VERSION);
                return 0;
            }
            if (snapshot.baseDate() != null) {
                clock.setBaseDate(snapshot.baseDate());
            }
            if (snapshot.clock() != null) {
                clock.resetTo(snapshot.clock());
            }
            int restored = staffing.restore(snapshot.roles());
            logger.info("读档完成：{} 个角色，第 {} 天 {}", restored,
                    snapshot.clock() == null ? 1 : snapshot.clock().day(), clock.currentDateTime());
            return restored;
        }).orElse(0);
    }

    public boolean paused() {
        return gate.paused();
    }

    public String pauseReason() {
        return gate.reason();
    }

    public SimClock clock() {
        return clock;
    }

    public Team team() {
        return team;
    }

    public ScheduleTable schedule() {
        return schedule;
    }

    /** 人员进出（Main 用它招默认团队，HR 工具用它动态上岗）。 */
    public Staffing staffing() {
        return staffing;
    }

    /** 事件投递器（测试/嵌入方观察路由结果用）。 */
    public EventRouter router() {
        return router;
    }

    /** 时钟线程/单步驱动器（测试可绕开线程直接 {@code tickOnce()}）。 */
    public ClockDriver driver() {
        return driver;
    }

    /** 班次反应（测试可观察/触发收尾兜底 {@code forceWrapUp()}）。 */
    public ShiftDirector director() {
        return director;
    }

    // ── CompanyView ────────────────────────────────────────────

    @Override
    public CompanyStatus status() {
        return new CompanyStatus(clock.nowDay(), clock.currentDateTime(), clock.describe(),
                gate.paused(), gate.reason(), team.snapshots());
    }

    @Override
    public List<RoleSpec> roster() {
        return team.specs();
    }

    @Override
    public void pause(String reason) {
        gate.pause(reason);
        driver.pause();
        logger.warn("公司已暂停：{}", reason);
    }

    @Override
    public void resume() {
        gate.resume();
        driver.resume();
        logger.info("公司已恢复");
    }

    // ── 快照组装 ───────────────────────────────────────────────

    private CompanySnapshot snapshot() {
        List<RoleSnapshot> roles = new ArrayList<>();
        for (Agent agent : team.agents()) {
            ConversationMemory.State conversation = agent.conversation().snapshot();
            roles.add(new RoleSnapshot(
                    agent.spec(),
                    agent.state(),
                    agent.pendingTasks().stream().map(Task::toRecord).toList(),
                    agent.history(0).stream().map(Task::toRecord).toList(),
                    conversation.day(),
                    conversation.messages()));
        }
        return new CompanySnapshot(SNAPSHOT_VERSION, Instant.now(), clock.nowDay(),
                baseDate(), roles);
    }

    /** 第 1 天对应的真实日历日期（直接读时钟的基准日，不做任何反推）。 */
    private LocalDate baseDate() {
        return clock.baseDate();
    }
}
