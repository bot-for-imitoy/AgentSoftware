package com.agent.software.company.store;

import com.agent.software.agent.role.RoleSnapshot;
import com.agent.software.sim.clock.DayTick;

import java.time.LocalDate;
import java.util.List;

/**
 * 整公司的持久化快照——运行时与磁盘之间唯一的交界。
 *
 * <p>由 {@code company.Company.save()} 组装，交给 {@code company.store.SnapshotStore} 落盘；
 * 快照里只有 model 类型，不含任何运行时对象。
 */
public record CompanySnapshot(int version, java.time.Instant savedAt,
                              DayTick clock, LocalDate baseDate,
                              List<RoleSnapshot> roles) {
}
