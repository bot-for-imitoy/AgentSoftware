package com.agent.software.sim.clock;

/** 日历坐标：第几天 + 当天第几 tick。 */
public record DayTick(int day, int tickOfDay) {
}
