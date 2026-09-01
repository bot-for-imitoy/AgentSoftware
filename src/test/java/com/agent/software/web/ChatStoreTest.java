package com.agent.software.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatStore 测试: 消息记录/增量拉取 + 甲方对话协调 (Web 界面数据源).
 */
class ChatStoreTest {

    // ── 消息记录 ──────────────────────────────────────────

    @Test
    void testRecordAndMessagesSince() {
        ChatStore store = new ChatStore();
        ChatStore.ChatMessage m1 = store.record("talk", "Leadership Group", "CEO", "林总", "COO", "陈总", "你好", "NORMAL");
        assertNotNull(m1);
        assertEquals(1L, m1.seq);
        store.record("talk", "Leadership Group", "COO", "陈总", "CEO", "林总", "收到", null);
        store.record("client", "Leadership Group", "CEO", "林总", "", "甲方", "请问需求是？", null);

        assertEquals(3, store.lastSeq());
        List<Map<String, Object>> all = store.messagesSince(0);
        assertEquals(3, all.size());
        assertEquals(1L, all.get(0).get("seq"));
        assertEquals("林总", all.get(0).get("fromName"));
        assertEquals("甲方", all.get(2).get("toName"));

        // 增量拉取: since=2 → 只剩 seq=3
        List<Map<String, Object>> inc = store.messagesSince(2);
        assertEquals(1, inc.size());
        assertEquals(3L, inc.get(0).get("seq"));
    }

    // ── 甲方对话协调 ──────────────────────────────────────

    @Test
    void testClientReplyRoundtrip() throws Exception {
        ChatStore store = new ChatStore();
        assertFalse(store.isClientWaitPending());

        assertNull(store.beginClientWait("CEO", "林总", "Leadership Group"));
        assertTrue(store.isClientWaitPending());
        assertEquals("林总", store.pendingHolderName());

        // 他人重复登记 → 拒绝
        String err = store.beginClientWait("COO", "陈总", "Leadership Group");
        assertTrue(err != null && err.contains("currently waiting"));

        // 甲方提交回复 → 返回记录的消息, 并唤醒等待者
        ChatStore.ChatMessage m = store.postClientReply("帮我开发一个支付系统");
        assertNotNull(m);
        assertEquals(ChatStore.KIND_CLIENT, m.kind);
        assertEquals("甲方", m.fromName);
        assertEquals("CEO", m.toRoleId);
        assertEquals("Leadership Group", m.group);

        String reply = store.awaitClientReply(5000);
        assertEquals("帮我开发一个支付系统", reply);

        // 结束后状态复位
        store.endClientWait();
        assertFalse(store.isClientWaitPending());
        assertNull(store.pendingHolderName());
        // 再次回复 → 无等待中的对话
        assertNull(store.postClientReply("又一条"));
    }

    @Test
    void testAwaitTimeout() throws Exception {
        ChatStore store = new ChatStore();
        store.beginClientWait("CEO", "林总", "Leadership Group");
        String reply = store.awaitClientReply(200);
        assertNull(reply, "超时应返回 null");
        store.endClientWait();
    }

    @Test
    void testAttachHeartbeat() throws InterruptedException {
        ChatStore store = new ChatStore();
        assertFalse(store.isAttached());
        store.markAttached();
        assertTrue(store.isAttached());
        // TTL 内保持挂载 (不 sleep 太久, 直接验证 TTL 常量合理性)
        assertTrue(ChatStore.ATTACH_TTL_MS >= TimeUnit.SECONDS.toMillis(30));
    }
}
