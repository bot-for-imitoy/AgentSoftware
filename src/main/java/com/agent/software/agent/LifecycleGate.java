package com.agent.software.agent;


import java.time.Duration;

/**
 * 全局暂停的唯一所有者。
 *
 * <p>master 把"暂停"存在 {@code AgentSystem} 的私有字段里，再用
 * {@code llm.setPauseGate(owner::isPaused)} 反向注入给 LLM 适配器；
 * 这里改为一个只读门：{@link TaskRunner} 与 LLM 适配器都只问它。
 */
public final class LifecycleGate {

    /** 暂停整个系统；重复调用只刷新原因。 */
    public void pause(String reason) {
        throw new UnsupportedOperationException("skeleton");
    }

    public void resume() {
        throw new UnsupportedOperationException("skeleton");
    }

    public boolean paused() {
        throw new UnsupportedOperationException("skeleton");
    }

    public String reason() {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 被暂停时挂起调用线程，直到恢复。 */
    public void awaitRunning(Duration poll) {
        throw new UnsupportedOperationException("skeleton");
    }
}
