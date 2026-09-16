package com.agent.software.engine;

import com.agent.software.model.AgentEvent;
import com.agent.software.model.CompanyStatus;
import com.agent.software.model.RoleSpec;
import com.agent.software.ports.CompanyView;
import com.agent.software.ports.SnapshotStore;

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
        throw new UnsupportedOperationException("skeleton");
    }

    public void stop() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 外部事件入口（客户消息、邮件通知、测试注入）。 */
    public void publish(AgentEvent event) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 落快照。 */
    public void save() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 读快照；返回恢复的角色数。 */
    public int restore() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean paused() {
        throw new UnsupportedOperationException("skeleton");
    }

    public String pauseReason() {
        throw new UnsupportedOperationException("skeleton");
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

    // ── CompanyView ────────────────────────────────────────────

    @Override
    public CompanyStatus status() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<RoleSpec> roster() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void pause(String reason) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void resume() {
        throw new UnsupportedOperationException("skeleton");
    }
}
