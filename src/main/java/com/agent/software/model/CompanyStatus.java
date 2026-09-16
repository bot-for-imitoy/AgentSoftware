package com.agent.software.model;

import java.util.List;

/** 整个公司的只读状态快照（供 Web / 控制台展示）。 */
public record CompanyStatus(DayTick clock, String dateTime, String describe,
                            boolean paused, String pauseReason,
                            List<AgentSnapshot> agents) {
}
