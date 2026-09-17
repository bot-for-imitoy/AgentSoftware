package com.agent.software.agent.task;

import com.agent.software.agent.AgentMailbox;
import com.agent.software.agent.AgentRuntime;
import com.agent.software.agent.LifecycleGate;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.transcript.Transcript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

/**
 * 单角色常驻工作循环（从 master {@code RolePool.roleLoop} 抽出）。
 *
 * <p>有序的等待链：全局暂停 → 可执行队列取任务 → 交给 {@link ToolLoop} →
 * 落结果 → 写轨迹。
 *
 * <p>注意它只认 {@link AgentRuntime} 一个窄接口（队列归 {@link AgentMailbox}），
 * 不认识 {@code agent.Agent}：
 * master 的 {@code RolePool} 拿到了整个角色对象，于是"取任务"这件小事
 * 也能顺手改状态、读私账，环就是这么来的。
 */
public final class TaskRunner implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(TaskRunner.class);

    /** 空闲轮询间隔（毫秒）。 */
    private static final long IDLE_POLL_MILLIS = 100L;

    private final AgentMailbox mailbox;
    private final AgentRuntime runtime;
    private final ToolLoop toolLoop;
    private final Transcript transcript;
    private final LifecycleGate gate;

    private volatile boolean running = true;
    private volatile Thread self;

    public TaskRunner(AgentMailbox mailbox, AgentRuntime runtime, ToolLoop toolLoop,
                      Transcript transcript, LifecycleGate gate) {
        this.mailbox = mailbox;
        this.runtime = runtime;
        this.toolLoop = toolLoop;
        this.transcript = transcript;
        this.gate = gate;
    }

    @Override
    public void run() {
        self = Thread.currentThread();
        RoleId me = runtime.id();
        logger.info("[{}] worker 循环开始", me.value());
        while (running && !Thread.currentThread().isInterrupted()) {
            // 全局暂停：不弹任务、不开始新工作（在飞的任务由 LLM 暂停门拦住）
            gate.awaitRunning(Duration.ofMillis(200));
            if (!running) {
                break;
            }
            Optional<Task> popped = mailbox.pop();
            if (popped.isEmpty()) {
                sleep(IDLE_POLL_MILLIS);
                continue;
            }
            execute(me, popped.get());
        }
        logger.info("[{}] worker 循环退出", me.value());
    }

    /** 执行一个任务：进入忙碌态 → 工具循环 → 落结果 → 写轨迹 → 回到空闲。 */
    private void execute(RoleId me, Task task) {
        runtime.beginTask(task);
        try {
            task.markRunning();
        } catch (RuntimeException e) {
            logger.warn("[{}] 任务 {} 状态异常，跳过", me.value(), task.id().value());
            runtime.finishTask(task);
            return;
        }
        logger.info("[{}] 开始任务 {}（{}）", me.value(), task.id().value(), task.description());
        int day = runtime.currentDay();
        boolean failed;
        try {
            ToolLoop.Outcome outcome = toolLoop.run(me, runtime.systemPrompt(), task,
                    runtime.conversation(), day);
            failed = outcome.failed();
            if (failed) {
                task.fail(outcome.answer());
            } else {
                task.complete(outcome.answer(), outcome.tokens());
            }
        } catch (RuntimeException e) {
            failed = true;
            task.fail("[ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            logger.error("[{}] 任务 {} 失败", me.value(), task.id().value(), e);
        }
        try {
            transcript.answer(me, task.result(), failed, task.tokens(),
                    new Transcript.TraceMeta(task.id(), null));
        } catch (RuntimeException e) {
            logger.debug("写轨迹失败：{}", e.getMessage());
        }
        logger.info("[{}] 任务 {} {}（{} tokens）", me.value(), task.id().value(),
                failed ? "失败" : "完成", task.tokens());
        runtime.finishTask(task);
    }

    public void requestStop() {
        running = false;
        Thread t = self;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
