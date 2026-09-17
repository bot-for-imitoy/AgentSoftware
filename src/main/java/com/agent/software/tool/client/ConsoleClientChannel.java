package com.agent.software.tool.client;

import java.time.Duration;
import com.agent.software.tool.client.ClientChannel.ClientQuestion;
import com.agent.software.tool.client.ClientChannel.ClientReply;

/**
 * 控制台客户通道：从 stdin 读取客户问题与回复，stdin 不可交互时视为离线。
 */
public final class ConsoleClientChannel implements ClientChannel {

    @Override
    public boolean interactive() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public ClientReply ask(ClientQuestion question, Duration timeout) {
        throw new UnsupportedOperationException("skeleton");
    }
}
