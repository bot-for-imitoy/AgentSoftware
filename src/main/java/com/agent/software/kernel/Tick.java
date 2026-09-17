package com.agent.software.kernel;

/** 绝对 tick（从第 1 天 08:00 起累计）。 */
public record Tick(long value) {

    public Tick plus(long ticks) {
        return new Tick(value + ticks);
    }

    public boolean before(Tick other) {
        return value < other.value;
    }

    public boolean after(Tick other) {
        return value > other.value;
    }
}
