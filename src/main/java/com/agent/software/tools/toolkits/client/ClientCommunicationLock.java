package com.agent.software.tools.toolkits.client;

/**
 * 与甲方沟通的全局互斥锁.
 *
 * talk_to_client 会阻塞等待用户输入, 同一时间只允许一位成员与甲方对话:
 * 锁被占用时, 其他成员调用 talk_to_client 会立即收到错误提示,
 * 避免多人同时抢占控制台输入.
 *
 * 进程级默认单例由 {@link #getInstance()} 获取; 每个 {@link
 * com.agent.software.AgentSystem} 直接持有自己的锁实例, 多系统互不阻塞.
 * 构造器公开, 便于测试与多实例场景创建独立实例.
 */
public final class ClientCommunicationLock {

    private static final ClientCommunicationLock INSTANCE = new ClientCommunicationLock();

    private String holderRoleId = null;
    private String holderName = null;

    public ClientCommunicationLock() {
    }

    public static ClientCommunicationLock getInstance() {
        return INSTANCE;
    }

    /**
     * 尝试获取与甲方沟通的独占权.
     *
     * @return null 表示获取成功; 否则返回错误描述 (当前持有者信息).
     *         同一角色重复获取视为成功 (可重入), 不会锁死自己.
     */
    public synchronized String tryAcquire(String roleId, String name) {
        if (holderRoleId != null && !holderRoleId.equals(roleId)) {
            return "another member " + holderName + " (" + holderRoleId
                    + ") is currently talking to the client";
        }
        holderRoleId = roleId;
        holderName = name;
        return null;
    }

    /** 释放锁 (仅持有者可释放). 幂等. */
    public synchronized void release(String roleId) {
        if (holderRoleId != null && holderRoleId.equals(roleId)) {
            holderRoleId = null;
            holderName = null;
        }
    }

    /** 当前是否被占用. */
    public synchronized boolean isHeld() {
        return holderRoleId != null;
    }

    /** 当前持有者描述 (未占用返回 null). */
    public synchronized String holderDescription() {
        return holderRoleId == null ? null : holderName + " (" + holderRoleId + ")";
    }
}
