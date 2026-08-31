package com.agent.software;

import com.agent.software.computers.Computer;
import com.agent.software.core.Types;
import com.agent.software.event.EventDispatcher;
import com.agent.software.event.TimeEventBus;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.role.RolePool;
import com.agent.software.store.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 系统管理类 (AgentSystem) — 统一管理 TimeEventBus + RolePool + 事件分发
 * (Python 版 agent_system.py).
 */
public class AgentSystem {

    private static final Logger logger = LoggerFactory.getLogger(AgentSystem.class);

    public final TimeEventBus timeManager;   // 共享时间源
    public final RolePool pool;
    public final EventDispatcher dispatcher;
    public final boolean autoToolkits;
    public final ConfigStore configStore;

    public AgentSystem(List<AgentRole> roles, List<String> roleIds,
                       double checkInterval, boolean autoToolkits) {
        this.timeManager = new TimeEventBus();
        this.timeManager.checkInterval = checkInterval;
        this.pool = new RolePool(null, null, timeManager, autoToolkits);
        this.dispatcher = new EventDispatcher(pool);
        this.autoToolkits = autoToolkits;
        this.configStore = new ConfigStore();

        // 时间线程的事件 → 事件分发器 (作息事件统一入口)
        this.timeManager.setEventSender(this::onTimeEvent);
        // 快进: 全部角色空闲时自动跳到下一个事件 Tick
        this.timeManager.setIdleChecker(this::allRolesIdle);

        List<AgentRole> all = new ArrayList<>();
        if (roles != null) {
            all.addAll(roles);
        }
        if (roleIds != null) {
            for (String rid : roleIds) {
                all.add(RoleLoader.getTemplate(rid));
            }
        }
        if (!all.isEmpty()) {
            addRoles(all);
        }
    }

    public AgentSystem() {
        this(null, null, 30.0, true);
    }

    // ── 角色管理 ──────────────────────────────────────────

    /** 批量注册角色: 耗时装配 (电脑创建 + MCP 服务器启动) 多线程并行. */
    public List<AgentRole> addRoles(List<AgentRole> roles) {
        // 先统一绑定共享时间源 (快, 串行)
        for (AgentRole role : roles) {
            role.bindTimeManager(timeManager);
        }
        if (autoToolkits) {
            // 并行装配: 每角色一个虚拟线程 (Java 21+), 用信号量限制并发数,
            // 避免 podman/npx 打满 (原固定线程池的 max_workers 语义不变)
            int maxWorkers = Math.min(10, roles.size());
            if (maxWorkers < 1) {
                maxWorkers = 1;
            }
            java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(maxWorkers);
            ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
            for (AgentRole role : roles) {
                ex.submit(() -> {
                    try {
                        gate.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        pool.setupRole(role);
                    } catch (Exception e) {
                        logger.error("AgentSystem: 角色 {} 装配失败 (电脑/MCP)", role.roleId, e);
                    } finally {
                        gate.release();
                    }
                });
            }
            ex.shutdown();
            try {
                ex.awaitTermination(10, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 按序注册 (含日志初始化)
        for (AgentRole role : roles) {
            pool.addRole(role);
            logger.info("AgentSystem: 角色已注册 {} ({})", role.roleId, role.name);
        }
        return roles;
    }

    /** 注册单个角色. */
    public AgentRole addRole(AgentRole role) {
        return addRoles(List.of(role)).get(0);
    }

    /** 注册全部默认管理角色 (CEO/COO/HR/CFO). */
    public List<AgentRole> addDefaultRoles() {
        List<AgentRole> roles = new ArrayList<>();
        for (String rid : RoleLoader.DEFAULT_ROLES) {
            roles.add(addRole(RoleLoader.getTemplate(rid)));
        }
        return roles;
    }

    public AgentRole getRole(String roleId) {
        return pool.getRole(roleId);
    }

    public Map<String, Map<String, Object>> getStatus() {
        return pool.getStatus();
    }

    // ── 事件与任务 ────────────────────────────────────────

    /** 时间线程作息事件的统一入口. */
    public void onTimeEvent(Types.Event event) {
        if (TimeEventBus.EVENT_SHIFT_START.equals(event.eventType)) {
            for (AgentRole role : pool.allRoles()) {
                // 上班唤醒; WAIT 角色本来就在岗等回复, 不重置
                if (role.state != Types.AgentState.ON_DUTY_IDLE
                        && role.state != Types.AgentState.WAIT) {
                    role.setState(Types.AgentState.ON_DUTY_IDLE);
                    logger.info("AgentSystem: SHIFT_START → {} 上班 (ON_DUTY_IDLE)", role.roleId);
                }
                // 上班自动开机
                try {
                    Computer comp = role.computerIfCreated();
                    if (comp != null && !comp.isOn()) {
                        comp.powerOn();
                        logger.info("AgentSystem: SHIFT_START → {} 电脑已自动开机", role.roleId);
                    }
                } catch (Exception e) {
                    logger.error("AgentSystem: {} 上班开机失败", role.roleId, e);
                }
            }
            pool.journalAll("全局通知: 上班 (SHIFT_START, 第 " + day() + " 天)");
        } else if (TimeEventBus.EVENT_SHIFT_END.equals(event.eventType)) {
            pool.journalAll("全局通知: 下班时间到 (SHIFT_END), 各角色总结后休息");
        }
        dispatcher.trigger(event);
    }

    /** 全部角色是否空闲 (供快进功能判定). 角色池为空视为不空闲. */
    public boolean allRolesIdle() {
        List<AgentRole> roles = pool.allRoles();
        if (roles.isEmpty()) {
            return false;
        }
        for (AgentRole r : roles) {
            if (r.isBusy() || r.queueDepth() > 0) {
                return false;
            }
        }
        return true;
    }

    /** 向事件总线投递事件, 广播给所有角色. */
    public Map<String, Map<String, Object>> trigger(Types.Event event) {
        return dispatcher.trigger(event);
    }

    /** 直接给指定角色分配任务. */
    public void assignTask(String roleId, AgentRole.Task task) {
        pool.assignTask(roleId, task);
    }

    // ── 生命周期 ──────────────────────────────────────────

    /** 启动系统: 角色池线程 + 时间线程. 启动时刻 = Tick 0 / 第 1 天. */
    public void start() {
        pool.start();
        timeManager.start();
        logger.info("AgentSystem 已启动: {}", describe());
    }

    /** 停止系统: 时间线程 + 角色池. */
    public void stop() {
        timeManager.stop();
        pool.shutdown(false);
        logger.info("AgentSystem 已停止");
    }

    // ── 时间查询 (转发共享 TimeEventBus) ───────────────────

    public int tick() {
        return timeManager.currentTick();
    }

    public int day() {
        return timeManager.dayNumber();
    }

    public String describe() {
        return timeManager.describe();
    }
}
