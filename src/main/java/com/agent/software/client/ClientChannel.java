package com.agent.software.client;

import com.agent.software.web.ChatStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户的"口头"通道：同一时间只允许和一个角色对话。
 *
 * <p>两个方向：
 * <ul>
 *   <li>客户发起：{@link #talk(String, String, boolean)}（Web/控制台 UI 调用）。</li>
 *   <li>角色发起：{@link #receiveFrom(String, String)}（{@code talk_to_client} 工具调用），忙则拒绝。</li>
 * </ul>
 *
 * <p>等待可以被 {@link #cancelWait(String)} 打断 —— 下班时由时间线程调用，
 * 避免甲方不回消息时把跨天滚动卡到超时。
 */
public final class ClientChannel {

    private static final Logger logger = LoggerFactory.getLogger(ClientChannel.class);
    private static final long REPLY_TIMEOUT_MILLIS = 300_000L;
    /** 每次最多睡这么久；wait/notify 通常能立即唤醒，这里是兜底。 */
    private static final long WAIT_SLICE_MILLIS = 1_000L;

    private final Client client;
    private final ChatStore store;
    private final Object lock = new Object();

    private String currentRoleId;

    private final Object waitLock = new Object();
    private String pendingReply;
    private boolean cancelled;
    private String cancelReason;

    public ClientChannel(Client client, ChatStore store) {
        this.client = client;
        this.store = store;
    }

    public Client getClient() {
        return client;
    }

    public boolean isFree() {
        synchronized (lock) {
            return currentRoleId == null;
        }
    }

    public String getCurrentRoleId() {
        synchronized (lock) {
            return currentRoleId;
        }
    }

    /** 客户发起一次对话；wait=true 时阻塞等待角色回复（可被 {@link #cancelWait(String)} 打断）。 */
    public String talk(String roleId, String message, boolean wait) {
        if (roleId == null || roleId.isBlank()) {
            return "client talk failed: no target role";
        }
        synchronized (lock) {
            if (currentRoleId != null && !currentRoleId.equals(roleId)) {
                return "client talk failed: already talking to " + currentRoleId;
            }
            currentRoleId = roleId;
        }
        beginWait();
        recordClientMessage(roleId, message);
        if (!wait) {
            return "client: message sent to " + roleId;
        }
        return awaitReply();
    }

    /** 角色发起一次对话；通道被别的角色占用时拒绝。返回等待到的回复（或超时/被打断提示）。 */
    public String receiveFrom(String roleId, String message) {
        synchronized (lock) {
            if (currentRoleId != null && !currentRoleId.equals(roleId)) {
                return "talk_to_client failed: the client is talking to " + currentRoleId;
            }
            currentRoleId = roleId;
        }
        beginWait();
        // 角色→客户这条消息由 Role.talkToClient 记录（那边才知道角色的显示名），这里不重复记
        return awaitReply();
    }

    /** 客户回复，唤醒等待方。 */
    public void reply(String text) {
        synchronized (waitLock) {
            pendingReply = text == null ? "" : text;
            cancelled = false;
            cancelReason = null;
            waitLock.notifyAll();
        }
    }

    /**
     * 打断当前等待（下班/停机时由时间线程调用）。等待方会立即返回 {@code reason}。
     * 空闲时调用无副作用。
     */
    public void cancelWait(String reason) {
        synchronized (waitLock) {
            cancelled = true;
            cancelReason = reason == null ? "client conversation cancelled" : reason;
            waitLock.notifyAll();
        }
    }

    /** 结束当前会话：释放占用，并清掉可能残留的回复。 */
    public void release() {
        synchronized (lock) {
            currentRoleId = null;
        }
        synchronized (waitLock) {
            pendingReply = null;
            cancelled = false;
            cancelReason = null;
        }
    }

    /**
     * 进入本轮等待：只清上一轮的取消标记，**不清回复**。
     * 前端 2s 轮询才启用输入框，但"角色把消息写进 ChatStore"到"真正进入等待"之间仍有窗口，
     * 若在这里清掉回复，用户抢在这之前回复就会丢。
     */
    private void beginWait() {
        synchronized (waitLock) {
            cancelled = false;
            cancelReason = null;
        }
    }

    private String awaitReply() {
        long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MILLIS;
        synchronized (waitLock) {
            while (true) {
                if (cancelled) {
                    String reason = cancelReason == null ? "client conversation cancelled" : cancelReason;
                    release();
                    return "[client] " + reason;
                }
                if (pendingReply != null) {
                    String reply = pendingReply;
                    pendingReply = null;
                    release();
                    return reply;
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    release();
                    return "[client] no reply within " + (REPLY_TIMEOUT_MILLIS / 1000) + "s";
                }
                try {
                    waitLock.wait(Math.min(remain, WAIT_SLICE_MILLIS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    release();
                    return "[client] interrupted while waiting for the client";
                }
            }
        }
    }

    /** 客户发出的消息：fromRoleId 空、fromName=Client A，前端据此渲染成甲方气泡。 */
    private void recordClientMessage(String toRoleId, String message) {
        if (store == null) {
            return;
        }
        try {
            store.record(ChatStore.KIND_CLIENT, "", "", ChatStore.CLIENT_NAME,
                    toRoleId, "", message, "");
        } catch (Exception e) {
            logger.warn("ClientChannel: failed to record message", e);
        }
    }
}
