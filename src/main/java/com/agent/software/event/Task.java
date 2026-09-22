package com.agent.software.event;

import com.agent.software.utils.DataRegistry;

import java.util.Map;

/**
 * 任务就是一种可执行事件：继承 {@link Event}，加上执行状态、结果与 token 消耗。
 *
 * <p>因为不是所有事件都是任务（SHIFT_START/NEW_MAIL/TALK 只是通知），Role 的 worker 按
 * {@link Event#type} 分流；Task 固定为 {@link EventType#TASK}。
 */
public class Task extends Event {

    public static final String DATA_TYPE = "task";

    static {
        DataRegistry.register(DATA_TYPE, () -> new Task(null, null, 0L, "", Priority.NORMAL));
    }

    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    public String status = PENDING;
    public String result = "";
    public int tokensConsumed = 0;

    public Task(String fromRoleId, String targetRoleId, long targetTime, String content, Priority priority) {
        super(fromRoleId, targetRoleId, targetTime, content);
        this.type = EventType.TASK;
        this.priority = priority == null ? Priority.NORMAL : priority;
    }

    public Task(String uuid, String fromRoleId, String targetRoleId, long targetTime,
                String content, Priority priority) {
        super(uuid, fromRoleId, targetRoleId, targetTime, content);
        this.type = EventType.TASK;
        this.priority = priority == null ? Priority.NORMAL : priority;
    }

    public void markRunning() {
        this.status = RUNNING;
    }

    public void markDone(String result, int tokens) {
        this.status = DONE;
        this.result = result == null ? "" : result;
        this.tokensConsumed = tokens;
    }

    public void markFailed(String err) {
        this.status = FAILED;
        this.result = err == null ? "" : err;
    }

    public boolean isFinished() {
        return DONE.equals(status) || FAILED.equals(status);
    }

    @Override
    public String type() {
        return DATA_TYPE;
    }

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = super.getData();
        d.put("status", status);
        d.put("result", result == null ? "" : result);
        d.put("tokens_consumed", Integer.toString(tokensConsumed));
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        super.loadData(data);
        if (data == null) {
            return;
        }
        this.status = data.getOrDefault("status", PENDING);
        this.result = data.getOrDefault("result", "");
        this.tokensConsumed = parseInt(data.get("tokens_consumed"), 0);
        this.type = EventType.TASK;
    }

    @Override
    public String toString() {
        return "Task(" + status + ", " + (fromRoleId == null ? "-" : fromRoleId) + "→"
                + (isBroadcast() ? "*" : targetRoleId) + ", " + priority + ")";
    }
}
