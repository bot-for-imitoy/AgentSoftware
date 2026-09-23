package com.agent.software.web;

import com.agent.software.client.Client;
import com.agent.software.client.ClientChannel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Web 消息存储 + 客户单会话通道。 */
class ChatStoreAndClientChannelTest {

    @Test
    void chatStoreRecordsAndReadsSinceSeq() {
        ChatStore store = new ChatStore();
        store.record(ChatStore.KIND_TALK, "Leadership Group", "CEO", "Lin", "COO", "Chen", "hi", "HIGH");
        assertEquals(1, store.messagesSince(0).size());
        long seq = store.lastSeq();
        assertEquals(0, store.messagesSince(seq).size());

        store.postClientReply("hello");
        assertEquals(1, store.messagesSince(seq).size());
        assertEquals(ChatStore.KIND_CLIENT, store.messagesSince(seq).get(0).get("kind"));
    }

    @Test
    void onlyOneConversationAtATime() {
        ChatStore store = new ChatStore();
        ClientChannel channel = new ClientChannel(new Client("CLIENT", "Client A", "client@x"), store);

        assertTrue(channel.isFree());
        String sent = channel.talk("CEO", "hello", false);
        assertTrue(sent.startsWith("client: message sent"), sent);
        assertFalse(channel.isFree());
        assertEquals("CEO", channel.getCurrentRoleId());

        String rejected = channel.receiveFrom("COO", "another topic");
        assertTrue(rejected.startsWith("talk_to_client failed"), rejected);

        channel.release();
        assertTrue(channel.isFree());
    }

    @Test
    void clientReplyCompletesWaitingTalk() throws Exception {
        ChatStore store = new ChatStore();
        ClientChannel channel = new ClientChannel(new Client("CLIENT", "Client A", "client@x"), store);

        CompletableFuture<String> reply = CompletableFuture.supplyAsync(() -> channel.talk("CTO", "q", true));
        Thread.sleep(100);
        channel.reply("answer");
        assertEquals("answer", reply.get(3, TimeUnit.SECONDS));
        assertTrue(channel.isFree());
    }

    @Test
    void cancelWaitUnblocksTheTalker() throws Exception {
        ChatStore store = new ChatStore();
        ClientChannel channel = new ClientChannel(new Client("CLIENT", "Client A", "client@x"), store);

        CompletableFuture<String> talking = CompletableFuture.supplyAsync(() -> channel.talk("CEO", "q", true));
        Thread.sleep(100);
        channel.cancelWait("[shift end] closed for today");

        String result = talking.get(3, TimeUnit.SECONDS);
        assertTrue(result.startsWith("[client] [shift end]"), result);
        assertTrue(channel.isFree(), "被打断后通道要释放，否则次日没人能再找客户");
    }

    @Test
    void receiveFromIsRejectedWhileAnotherRoleIsTalking() {
        ChatStore store = new ChatStore();
        ClientChannel channel = new ClientChannel(new Client("CLIENT", "Client A", "client@x"), store);
        channel.talk("CEO", "hello", false);
        assertTrue(channel.receiveFrom("CTO", "hi").startsWith("talk_to_client failed"));
    }

    @Test
    void replyArrivingBeforeTheWaitStartsIsNotLost() {
        ChatStore store = new ChatStore();
        ClientChannel channel = new ClientChannel(new Client("CLIENT", "Client A", "client@x"), store);

        // 角色已占用通道（前端据此启用输入框），但还没进 awaitReply
        channel.talk("CEO", "question", false);
        channel.reply("early answer");

        // 同一角色随后进入等待，应立刻拿到这条早到的回复
        assertEquals("early answer", channel.receiveFrom("CEO", "question"));
        assertTrue(channel.isFree());
    }

    /** 客户主动找某个成员：口信必须真的投给目标角色（早期实现只记录不投递）。 */
    @Test
    void clientMessageIsDeliveredToTheTargetRole() {
        ChatStore store = new ChatStore();
        ClientChannel channel = new ClientChannel(new Client("CLIENT", "Client A", "client@x"), store);
        List<String> delivered = new CopyOnWriteArrayList<>();
        channel.setTalkSink((roleId, message) -> delivered.add(roleId + "|" + message));

        String out = channel.talk("CTO", "hello there", false);
        assertTrue(out.startsWith("client: message sent"), out);
        assertEquals(List.of("CTO|hello there"), delivered, "客户的口信必须投给目标角色");
        assertEquals(1, store.messagesSince(0).size(), "客户消息也要进活动流");
    }

    /** 客户可以改找别人（等于结束上一段会话），也可以显式结束会话。 */
    @Test
    void clientCanSwitchAddresseeAndEndTheChat() {
        ChatStore store = new ChatStore();
        ClientChannel channel = new ClientChannel(new Client("CLIENT", "Client A", "client@x"), store);

        assertTrue(channel.talk("CEO", "hi", false).startsWith("client: message sent"));
        assertEquals("CEO", channel.getCurrentRoleId());

        assertTrue(channel.talk("CTO", "hi", false).startsWith("client: message sent"));
        assertEquals("CTO", channel.getCurrentRoleId(), "客户改找别人时应换掉会话对象");

        channel.release();
        assertTrue(channel.isFree(), "结束会话后通道要空出来");
        assertNull(channel.getCurrentRoleId());
    }

    /** 目标角色正在等客户回复时，客户的消息应当成交付给他的回复，而不是再排一条事件。 */
    @Test
    void clientMessageToTheWaitingRoleIsHandedOverAsTheReply() throws Exception {
        ChatStore store = new ChatStore();
        ClientChannel channel = new ClientChannel(new Client("CLIENT", "Client A", "client@x"), store);

        CompletableFuture<String> waiting = CompletableFuture.supplyAsync(() -> channel.receiveFrom("CTO", "ask"));
        Thread.sleep(150);   // 让它进入 awaitReply

        List<String> delivered = new CopyOnWriteArrayList<>();
        channel.setTalkSink((roleId, message) -> delivered.add(roleId + "|" + message));

        assertTrue(channel.talk("CTO", "the answer", false).startsWith("client: replied"));
        assertEquals("the answer", waiting.get(3, TimeUnit.SECONDS));
        assertTrue(delivered.isEmpty(), "正在等的角色应直接收到回复，而不是再排一条事件");
        assertTrue(channel.isFree());
    }
}
