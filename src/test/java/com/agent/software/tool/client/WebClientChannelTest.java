package com.agent.software.tool.client;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.tool.client.ClientChannel.ClientQuestion;
import com.agent.software.tool.client.ClientChannel.ClientReply;
import com.agent.software.transcript.ChatFeed;
import com.agent.software.transcript.Transcript;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebClientChannel} 测试（迁移自 master {@code tools/toolkits/client/TalkToClientWebTest}
 * 的 Web 客户通道部分）。
 *
 * <p>覆盖：提问写进 {@link ChatFeed}、{@code awaitClientReply} 会合点、超时返回
 * {@link ClientReply#unavailable}、未附着/未绑定轨迹的可读错误、实例级互斥锁。
 */
class WebClientChannelTest {

    private static final RoleId CEO = new RoleId("CEO");

    private static void await(String what, long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), "等待超时：" + what);
    }

    private static ClientQuestion question(String text) {
        return new ClientQuestion(CEO, "Lin Zong", "Leadership Group", text);
    }

    // ── 往返：提问进 feed，回复从会合点取回 ────────────────────

    @Test
    void 提问写入feed并在客户回复后返回() throws InterruptedException {
        ChatFeed feed = new ChatFeed();
        feed.touchAttach();
        WebClientChannel channel = new WebClientChannel(feed);
        assertTrue(channel.interactive());

        AtomicReference<ClientReply> result = new AtomicReference<>();
        Thread caller = new Thread(() -> result.set(
                channel.ask(question("请问项目需求是什么？"), Duration.ofSeconds(5))));
        caller.start();

        // 组长已进入等待客户回复的状态
        await("组长进入等待客户回复", 5_000,
                () -> feed.clientDialogue().waitingRoleId() != null);
        assertEquals("CEO", feed.clientDialogue().waitingRoleId());
        assertEquals("Lin Zong", feed.clientDialogue().waitingName());
        assertEquals("请问项目需求是什么？", feed.clientDialogue().pendingQuestion());

        // 提问本身也进了轨迹
        List<Transcript.Entry> entries = feed.since(0);
        assertEquals(1, entries.size());
        assertEquals(ChatFeed.KIND_CLIENT, entries.get(0).kind());
        assertEquals("请问项目需求是什么？", entries.get(0).text());
        assertEquals("Lin Zong", entries.get(0).fromName());
        assertEquals("Leadership Group", entries.get(0).group());

        // 客户在页面上回复
        feed.submitClientReply("请帮我做一个支付系统");
        caller.join(5_000);
        assertFalse(caller.isAlive());

        assertTrue(result.get().delivered(), result.get().reason());
        assertEquals("请帮我做一个支付系统", result.get().text());
        assertEquals(null, feed.clientDialogue().waitingRoleId(), "回复后等待状态应复位");
    }

    // ── 超时 / 不可用 ──────────────────────────────────────────

    @Test
    void 超时返回不可用() {
        ChatFeed feed = new ChatFeed();
        feed.touchAttach();
        WebClientChannel channel = new WebClientChannel(feed);

        ClientReply reply = channel.ask(question("有人在吗"), Duration.ofMillis(80));
        assertFalse(reply.delivered());
        assertTrue(reply.reason().contains("超时"), reply.reason());
    }

    @Test
    void 浏览器未附着时返回不可用() {
        ChatFeed feed = new ChatFeed();
        WebClientChannel channel = new WebClientChannel(feed);
        assertFalse(channel.interactive());

        ClientReply reply = channel.ask(question("有人在吗"), Duration.ofSeconds(1));
        assertFalse(reply.delivered());
        assertTrue(reply.reason().contains("未附着"), reply.reason());
        // 提问仍应写进轨迹，供 UI 展示
        assertEquals(1, feed.since(0).size());
    }

    @Test
    void 未绑定轨迹时返回不可用() {
        WebClientChannel channel = new WebClientChannel(null);
        assertFalse(channel.interactive());

        ClientReply reply = channel.ask(question("有人在吗"), Duration.ofSeconds(1));
        assertFalse(reply.delivered());
        assertTrue(reply.reason().contains("未绑定轨迹"), reply.reason());
    }

    // ── 互斥：同一时刻只允许一位组长找客户 ─────────────────────

    @Test
    void 互斥锁拒绝第二个提问者() throws InterruptedException {
        ChatFeed feed = new ChatFeed();
        feed.touchAttach();
        WebClientChannel channel = new WebClientChannel(feed);

        AtomicReference<ClientReply> first = new AtomicReference<>();
        Thread firstCaller = new Thread(() -> first.set(
                channel.ask(question("第一位组长的提问"), Duration.ofSeconds(5))));
        firstCaller.start();

        await("第一位组长进入等待", 5_000, () -> feed.clientDialogue().waitingRoleId() != null);

        // 第二位组长：拿不到锁，立刻拿到可读错误（不会阻塞、不会写进轨迹）
        ClientReply second = channel.ask(
                new ClientQuestion(new RoleId("COO"), "Chen Zong", "Leadership Group", "第二位组长的提问"),
                Duration.ofSeconds(1));
        assertFalse(second.delivered());
        assertTrue(second.reason().contains("已有同事正在与客户沟通"), second.reason());
        assertEquals(1, feed.since(0).size(), "被互斥拒绝的提问不应写进轨迹");

        // 释放后第一位组长拿到回复
        feed.submitClientReply("先回答第一位");
        firstCaller.join(5_000);
        assertFalse(firstCaller.isAlive());
        assertTrue(first.get().delivered());
        assertEquals("先回答第一位", first.get().text());
    }
}
