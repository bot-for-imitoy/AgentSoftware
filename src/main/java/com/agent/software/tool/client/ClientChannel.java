package com.agent.software.tool.client;

import com.agent.software.kernel.Ids.RoleId;

import java.time.Duration;

/**
 * 与"客户/用户"对话的通道（控制台或 Web 二选一）。
 *
 * <p>互斥（同一时刻只能有一位组长在找客户）由实现内部保证；
 * master 是一个全局单例 {@code ClientCommunicationLock}。
 */
public interface ClientChannel {

    /** 是否可用（Web 通道需要浏览器心跳；控制台需要 stdin）。 */
    boolean interactive();

    ClientReply ask(ClientQuestion question, Duration timeout);

    record ClientQuestion(RoleId asker, String askerName, String group, String text) {
    }

    record ClientReply(boolean delivered, String text, String reason) {

        public static ClientReply of(String text) {
            throw new UnsupportedOperationException("skeleton");
        }

        public static ClientReply unavailable(String reason) {
            throw new UnsupportedOperationException("skeleton");
        }
    }
}
