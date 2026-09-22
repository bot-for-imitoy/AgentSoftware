package com.agent.software.client;

import com.agent.software.web.ChatStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 客户的"口头"通道：同一时间只允许和一个角色对话。
 *
 * <p>两个方向：
 * <ul>
 *   <li>客户发起：{@link #talk(String, String, boolean)}（Web/控制台 UI 调用）。</li>
 *   <li>角色发起：{@link #receiveFrom(String, String)}（{@code talk_to_client} 工具调用），忙则拒绝。</li>
 * </ul>
 */
public final class ClientChannel {

    private static final Logger logger = LoggerFactory.getLogger(ClientChannel.class);
    private static final long REPLY_TIMEOUT_MILLIS = 300_000L;

    private final Client client;
    private final ChatStore store;
    private final Object lock = new Object();

    private String currentRoleId;
    private final BlockingQueue<String> replies = new ArrayBlockingQueue<>(16);

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

    /** 客户发起一次对话；wait=true 时阻塞等待角色回复。 */
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
        record("client", roleId, message);
        if (!wait) {
            return "client: message sent to " + roleId;
        }
        return awaitReply();
    }

    /** 角色发起一次对话；通道被别的角色占用时拒绝。返回角色的到回复（或超时提示）。 */
    public String receiveFrom(String roleId, String message) {
        synchronized (lock) {
            if (currentRoleId != null && !currentRoleId.equals(roleId)) {
                return "talk_to_client failed: the client is talking to " + currentRoleId;
            }
            currentRoleId = roleId;
        }
        record("client", roleId, message);
        return awaitReply();
    }

    /** 客户回复。 */
    public void reply(String text) {
        replies.offer(text == null ? "" : text);
    }

    public void release() {
        synchronized (lock) {
            currentRoleId = null;
        }
    }

    private String awaitReply() {
        try {
            String reply = replies.poll(REPLY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            if (reply == null) {
                return "[client] no reply within " + (REPLY_TIMEOUT_MILLIS / 1000) + "s";
            }
            return reply;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "[client] interrupted while waiting for the client";
        } finally {
            release();
        }
    }

    private void record(String kind, String roleId, String message) {
        if (store == null) {
            return;
        }
        try {
            store.record(kind, "", roleId, roleId, client.clientId, client.name, message, "");
        } catch (Exception e) {
            logger.warn("ClientChannel: failed to record message", e);
        }
    }
}
