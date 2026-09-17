package com.agent.software.tool.client;

import com.agent.software.transcript.ChatFeed;

import java.time.Duration;
import com.agent.software.tool.client.ClientChannel.ClientQuestion;
import com.agent.software.tool.client.ClientChannel.ClientReply;

/**
 * Web 客户通道：经 ChatFeed 的等待/回复会合点与浏览器交换客户消息。
 */
public final class WebClientChannel implements ClientChannel {

    /** 绑定 Web 端的轨迹与会合点。 */
    public WebClientChannel(ChatFeed feed) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public boolean interactive() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public ClientReply ask(ClientQuestion question, Duration timeout) {
        throw new UnsupportedOperationException("skeleton");
    }
}
