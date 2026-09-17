package com.agent.software.sim.clock;

import com.agent.software.kernel.Tick;
/**
 * 时钟每步的观察者。
 *
 * <p>时钟只认识时间，不认识公司：班次反应（上下班、收尾兜底）由 {@code company.ShiftDirector}
 * 作为观察者实现，bootstrap 负责注册。这样 {@code sim} 不需要依赖 {@code agent}。
 */
@FunctionalInterface
public interface TickObserver {

    /** 时钟每推进一次调用；实现方自行判断是否处在班次边界。 */
    void onTick(Tick now);
}
