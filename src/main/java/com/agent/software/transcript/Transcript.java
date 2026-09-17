package com.agent.software.transcript;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.kernel.Payload;

import java.util.List;

/**
 * 运行轨迹写入端口（推理 / 工具调用 / 最终答案 / 群聊 / 客户消息 / 系统通知）。
 *
 * <p>engine 只负责写；Web 端通过内嵌的 {@link Feed} 读取视图增量拉取。
 * master 里这套逻辑与聊天记录、客户等待状态一起挤在 {@code ChatStore}。
 */
public interface Transcript {

    void reasoning(RoleId agent, String text, TraceMeta meta);

    void note(RoleId agent, String text, TraceMeta meta);

    void toolCall(RoleId agent, String toolName, String argsJson, String result, TraceMeta meta);

    void answer(RoleId agent, String text, boolean failed, int tokens, TraceMeta meta);

    void talk(Talk record);

    void client(Client record);

    void system(String text);

    record TraceMeta(TaskId taskId, Integer round) {
    }

    record Talk(RoleId from, String fromName, RoleId to, String toName,
                String group, String text, String urgency) {
    }

    record Client(RoleId role, String name, String group, String text) {
    }

    /** 供 Web 端增量拉取的读取视图（由适配器实现）。 */
    interface Feed extends Transcript {

        long watermark();

        List<Entry> since(long seq);
    }

    /** Web 端消费的一条轨迹记录。 */
    record Entry(long seq, long timestamp, String kind, String group,
                 String fromRoleId, String fromName, String toRoleId, String toName,
                 String text, Payload extra) {
    }
}
