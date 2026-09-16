package com.agent.software.adapters.input;

import com.agent.software.ports.ClientChannel;

import java.time.Duration;

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
