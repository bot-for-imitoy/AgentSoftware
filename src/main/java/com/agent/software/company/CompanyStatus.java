package com.agent.software.company;

import com.agent.software.agent.AgentSnapshot;
import com.agent.software.sim.clock.DayTick;

import java.util.List;

/** 整个公司的只读状态快照（供 Web / 控制台展示）。 */
public record CompanyStatus(DayTick clock, String dateTime, String describe,
                            boolean paused, String pauseReason,
                            List<AgentSnapshot> agents) {
}
