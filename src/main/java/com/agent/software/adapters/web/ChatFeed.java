package com.agent.software.adapters.web;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.Payload;
import com.agent.software.ports.Transcript;

import java.util.List;

/**
 * 内存环形缓冲的轨迹流 + 客户回复会合点：Web 端按 watermark 增量拉取，客户输入阻塞式等待。
 */
public final class ChatFeed implements Transcript.Feed {

    @Override
    public void reasoning(RoleId agent, String text, TraceMeta meta) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void note(RoleId agent, String text, TraceMeta meta) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void toolCall(RoleId agent, String toolName, String argsJson, String result, TraceMeta meta) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void answer(RoleId agent, String text, boolean failed, int tokens, TraceMeta meta) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void talk(Talk record) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void client(Client record) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void system(String text) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public long watermark() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Entry> since(long seq) {
        throw new UnsupportedOperationException("skeleton");
    }
}
