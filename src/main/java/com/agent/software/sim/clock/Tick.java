package com.agent.software.sim.clock;

/** 绝对 tick（从第 1 天 08:00 起累计）。 */
public record Tick(long value) {

    public Tick plus(long ticks) {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean before(Tick other) {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean after(Tick other) {
        throw new UnsupportedOperationException("skeleton");
    }
}
