package com.agent.software.tool.client;

import com.agent.software.transcript.ChatFeed;
import com.agent.software.transcript.Transcript;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import com.agent.software.tool.client.ClientChannel.ClientQuestion;
import com.agent.software.tool.client.ClientChannel.ClientReply;

/**
 * Web 客户通道：经 ChatFeed 的等待/回复会合点与浏览器交换客户消息。
 *
 * <p>提问先写进轨迹（同时充当"启用输入框 + 展示谁在等"的握手），再阻塞等待
 * {@link ChatFeed#awaitClientReply(Duration)}；浏览器未附着时直接返回不可用，避免白等一个超时。
 *
 * <p><b>互斥</b>：实例级 {@link ReentrantLock}（bootstrap 共享同一实例），
 * 两个实现各自保证"同一时刻只允许一位组长找客户"。
 */
public final class WebClientChannel implements ClientChannel {

    private final ChatFeed feed;
    private final ReentrantLock lock = new ReentrantLock();

    /** 绑定 Web 端的轨迹与会合点。 */
    public WebClientChannel(ChatFeed feed) {
        this.feed = feed;
    }

    @Override
    public boolean interactive() {
        return feed != null && feed.clientAttached();
    }

    @Override
    public ClientReply ask(ClientQuestion question, Duration timeout) {
        if (!lock.tryLock()) {
            return ClientReply.unavailable("已有同事正在与客户沟通");
        }
        try {
            if (feed == null) {
                return ClientReply.unavailable("Web 客户通道未绑定轨迹");
            }
            if (question != null) {
                feed.client(new Transcript.Client(question.asker(), question.askerName(),
                        question.group(), question.text()));
            }
            if (!feed.clientAttached()) {
                return ClientReply.unavailable("客户暂时联系不上（Web 端未附着）");
            }
            Optional<String> reply = feed.awaitClientReply(timeout);
            if (reply.isEmpty()) {
                return ClientReply.unavailable("客户未在超时时间内回复");
            }
            return ClientReply.of(reply.get());
        } finally {
            lock.unlock();
        }
    }
}
