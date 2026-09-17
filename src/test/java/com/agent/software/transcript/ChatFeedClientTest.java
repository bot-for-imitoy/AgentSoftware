package com.agent.software.transcript;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.transcript.ChatFeed.ClientDialogue;
import com.agent.software.transcript.Transcript.Client;
import com.agent.software.transcript.Transcript.Entry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatFeed} 的客户回复会合点（浏览器在线心跳 / 等待 / 超时 / 中断）。
 *
 * <p>master 对应 {@code io.WebInputTest} + {@code web.ChatStoreTest} 的客户对话部分：
 * master 把"等待状态"和"浏览器在线"拆在 {@code ChatStore} 与
 * {@code ClientCommunicationLock} 两处；新架构收进 {@link ChatFeed} 一个会合点。
 */
class ChatFeedClientTest {

    private static final RoleId CEO = new RoleId("CEO");

    // ── 浏览器在线心跳 ─────────────────────────────────────────

    @Test
    void 未附加时不在线() {
        ChatFeed feed = new ChatFeed();
        assertFalse(feed.clientAttached(), "没有浏览器时应为离线");
        feed.touchAttach();
        assertTrue(feed.clientAttached());
    }

    @Test
    void 心跳超时后变为离线() throws Exception {
        ChatFeed feed = new ChatFeed();
        feed.touchAttach();
        assertTrue(feed.clientAttached());

        expireHeartbeat(feed);
        assertFalse(feed.clientAttached(), "心跳超过 TTL 后应视为离线");

        // 再刷新心跳又在线
        feed.touchAttach();
        assertTrue(feed.clientAttached());
    }

    // ── 客户往来状态 ───────────────────────────────────────────

    @Test
    void clientDialogue反映提问方与在线状态() {
        ChatFeed feed = new ChatFeed();
        feed.client(new Client(CEO, "林总", "Leadership Group", "请问要做什么系统？"));

        ClientDialogue dialogue = feed.clientDialogue();
        assertFalse(dialogue.attached());
        assertNull(dialogue.waitingRoleId(), "没有等待者时不应报告在等");
        assertEquals("请问要做什么系统？", dialogue.pendingQuestion());

        feed.touchAttach();
        assertTrue(feed.clientDialogue().attached());
    }

    // ── 会合 ───────────────────────────────────────────────────

    @Test
    void 等待中被submit唤醒() throws Exception {
        ChatFeed feed = new ChatFeed();
        feed.client(new Client(CEO, "林总", "Leadership Group", "请问要做什么系统？"));
        feed.touchAttach();

        AtomicReference<Optional<String>> result = new AtomicReference<>();
        Thread waiter = new Thread(() -> result.set(feed.awaitClientReply(Duration.ofSeconds(5))));
        waiter.start();
        try {
            await("等待者注册", 5_000, () -> feed.clientDialogue().waitingRoleId() != null);
            assertEquals("CEO", feed.clientDialogue().waitingRoleId());
            assertEquals("林总", feed.clientDialogue().waitingName());

            assertTrue(feed.submitClientReply("请做一个支付系统"), "有等待者时 submit 应报告已投递");
            waiter.join(5_000);
            assertFalse(waiter.isAlive(), "收到回复后等待应结束");
            assertEquals("请做一个支付系统", result.get().orElseThrow());
            assertNull(feed.clientDialogue().waitingRoleId(), "结束后不再处于等待");
        } finally {
            waiter.join(1_000);
        }
    }

    @Test
    void 超时返回空() {
        ChatFeed feed = new ChatFeed();
        feed.client(new Client(CEO, "林总", "Leadership Group", "问题"));

        long start = System.currentTimeMillis();
        Optional<String> reply = feed.awaitClientReply(Duration.ofMillis(150));
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(reply.isEmpty(), "超时应返回空");
        assertTrue(elapsed >= 100L, "应真的等到超时，实际 " + elapsed + "ms");
        assertNull(feed.clientDialogue().waitingRoleId());
    }

    @Test
    void 无等待者时先存pending下一次await立即取走() {
        ChatFeed feed = new ChatFeed();
        feed.client(new Client(CEO, "林总", "Leadership Group", "问题"));

        assertFalse(feed.submitClientReply("先到的回复"), "没有等待者时返回 false（已暂存）");
        assertNull(feed.clientDialogue().waitingRoleId());

        // 下一次 await 立即取走暂存的回复，不再阻塞
        Optional<String> reply = feed.awaitClientReply(Duration.ofSeconds(2));
        assertEquals("先到的回复", reply.orElseThrow());

        // 取走后不再残留
        assertTrue(feed.awaitClientReply(Duration.ofMillis(80)).isEmpty());
    }

    @Test
    void submitClientReply写入客户轨迹() {
        ChatFeed feed = new ChatFeed();
        feed.client(new Client(CEO, "林总", "Leadership Group", "问题"));
        feed.submitClientReply("客户答复");

        List<Entry> all = feed.since(0);
        Entry last = all.get(all.size() - 1);
        assertEquals(ChatFeed.KIND_CLIENT, last.kind());
        assertEquals(ChatFeed.CLIENT_NAME, last.fromName());
        assertEquals("客户答复", last.text());
        // 没有等待者时 toRoleId 留空（消息仍进轨迹供前端渲染）
        assertEquals("", last.toRoleId());
    }

    @Test
    void 线程中断时恢复中断位并返回空() throws Exception {
        ChatFeed feed = new ChatFeed();
        AtomicReference<Optional<String>> result = new AtomicReference<>();
        AtomicBoolean interruptedAfter = new AtomicBoolean();

        Thread waiter = new Thread(() -> {
            result.set(feed.awaitClientReply(null));
            interruptedAfter.set(Thread.currentThread().isInterrupted());
        });
        waiter.start();
        try {
            await("等待者注册", 5_000, () -> feed.clientDialogue().waitingRoleId() != null);
            waiter.interrupt();
            waiter.join(5_000);
            assertFalse(waiter.isAlive(), "中断后等待应立即结束");
            assertTrue(result.get().isEmpty(), "中断应返回空");
            assertTrue(interruptedAfter.get(), "await 必须恢复中断位");
            assertNull(feed.clientDialogue().waitingRoleId());
        } finally {
            waiter.join(1_000);
        }
    }

    // ── 助手 ───────────────────────────────────────────────────

    /** 把心跳时间戳拨回到 TTL 之前（避免为了等过期而真的睡 15 秒）。 */
    private static void expireHeartbeat(ChatFeed feed) throws Exception {
        Field field = ChatFeed.class.getDeclaredField("lastAttachMillis");
        field.setAccessible(true);
        field.setLong(feed, System.currentTimeMillis() - ChatFeed.ATTACH_TTL_MS - 1_000L);
    }

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
}
