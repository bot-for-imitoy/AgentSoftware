package com.agent.software.web;

import com.agent.software.client.Client;
import com.agent.software.client.ClientChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
