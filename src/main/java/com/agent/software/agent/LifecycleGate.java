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

    private final Object lock = new Object();
    private volatile boolean paused;
    private volatile String reason = "";

    /** 暂停整个系统；重复调用只刷新原因。 */
    public void pause(String reason) {
        synchronized (lock) {
            this.paused = true;
            this.reason = reason == null ? "" : reason;
            lock.notifyAll();
        }
    }

    public void resume() {
        synchronized (lock) {
            this.paused = false;
            this.reason = "";
            lock.notifyAll();
        }
    }

    public boolean paused() {
        return paused;
    }

    public String reason() {
        return reason;
    }

    /** 被暂停时挂起调用线程，直到恢复。 */
    public void awaitRunning(Duration poll) {
        long millis = poll == null ? 200L : Math.max(10L, poll.toMillis());
        while (paused && !Thread.currentThread().isInterrupted()) {
            synchronized (lock) {
                if (!paused) {
                    return;
                }
                try {
                    lock.wait(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
