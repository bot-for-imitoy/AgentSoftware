package com.agent.software.transcript;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.transcript.Transcript.Client;
import com.agent.software.transcript.Transcript.Entry;
import com.agent.software.transcript.Transcript.Talk;
import com.agent.software.transcript.Transcript.TraceMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChatFeed} 的轨迹写入 / 增量拉取 / 环形缓冲淘汰。
 *
 * <p>master 对应 {@code web.ChatStoreTest} 的消息记录部分：master 用可变
 * {@code ChatMessage} + 裸 {@code Map} 序列化，新架构的读取视图是 {@link Entry} record，
 * 增量语义（watermark / since）与 kind 命名保持不变。
 */
class ChatFeedTest {

    private static final RoleId DEV = new RoleId("dev_1");
    private static final TaskId TASK = new TaskId("task-1");

    private static TraceMeta meta(Integer round) {
        return new TraceMeta(TASK, round);
    }

    @Test
    void 记录与增量拉取() {
        ChatFeed feed = new ChatFeed();
        assertEquals(0L, feed.watermark(), "空 feed 的 watermark 为 0");
        assertTrue(feed.since(0).isEmpty());

        feed.system("系统启动");
        feed.reasoning(DEV, "思考中", meta(1));
        feed.note(DEV, "中间叙述", meta(1));
        feed.toolCall(DEV, "get_time", "{\"zone\":\"UTC\"}", "10:00 am", meta(1));
        feed.answer(DEV, "最终答案", false, 42, meta(null));

        assertEquals(5L, feed.watermark());
        assertEquals(5, feed.since(0).size());
        assertEquals(1L, feed.since(0).get(0).seq(), "seq 从 1 开始单调递增");
        assertEquals(5L, feed.since(0).get(4).seq());

        // 增量：since=2 → 只剩 seq 3/4/5
        List<Entry> incremental = feed.since(2);
        assertEquals(3, incremental.size());
        assertEquals(3L, incremental.get(0).seq());
        assertEquals(5L, incremental.get(2).seq());

        // since=watermark → 空；拉到的是拷贝，外部改动不影响内部
        assertTrue(feed.since(feed.watermark()).isEmpty());
        assertTrue(feed.since(100).isEmpty());
    }

    @Test
    void 各kind的Entry字段() {
        RoleId other = new RoleId("dev_2");
        ChatFeed feed = new ChatFeed(id -> {
            if (DEV.equals(id)) {
                return new ChatFeed.RoleInfo("张三", "研发组");
            }
            if (other.equals(id)) {
                return new ChatFeed.RoleInfo("李四", "研发组");
            }
            return null;
        });

        feed.reasoning(DEV, "先想一下", meta(2));
        feed.note(DEV, "记个中间结论", meta(2));
        feed.toolCall(DEV, "get_time", "{\"zone\":\"UTC\"}", "10:00 am", meta(2));
        feed.answer(DEV, "结论", false, 7, meta(null));
        feed.answer(DEV, "炸了", true, 0, meta(null));
        feed.talk(new Talk(DEV, "张三", other, "李四", "研发组", "你好", "HIGH"));
        feed.talk(new Talk(DEV, "张三", other, "李四", "研发组", "在吗", null));
        feed.client(new Client(DEV, "张三", "研发组", "客户要什么？"));
        feed.system("系统提示");

        List<Entry> all = feed.since(0);
        assertEquals(9, all.size());

        // reason
        Entry reason = all.get(0);
        assertEquals(ChatFeed.KIND_REASON, reason.kind());
        assertEquals("研发组", reason.group());
        assertEquals("dev_1", reason.fromRoleId());
        assertEquals("张三", reason.fromName());
        assertEquals("", reason.toRoleId());
        assertEquals("", reason.toName());
        assertEquals("先想一下", reason.text());
        assertEquals(TASK.value(), reason.extra().asMap().get("taskId"));
        assertEquals(2, reason.extra().asMap().get("round"));

        // note
        Entry note = all.get(1);
        assertEquals(ChatFeed.KIND_NOTE, note.kind());
        assertEquals("记个中间结论", note.text());
        assertEquals(2, note.extra().asMap().get("round"));

        // tool：text 故意留空，工具信息全在 extra
        Entry tool = all.get(2);
        assertEquals(ChatFeed.KIND_TOOL, tool.kind());
        assertEquals("", tool.text());
        assertEquals("get_time", tool.extra().asMap().get("tool"));
        assertEquals("{\"zone\":\"UTC\"}", tool.extra().asMap().get("args"));
        assertEquals("10:00 am", tool.extra().asMap().get("result"));
        assertEquals(2, tool.extra().asMap().get("round"));

        // answer
        Entry done = all.get(3);
        assertEquals(ChatFeed.KIND_ANSWER, done.kind());
        assertEquals("结论", done.text());
        assertEquals("done", done.extra().asMap().get("status"));
        assertEquals(7, done.extra().asMap().get("tokens"));
        assertEquals("failed", all.get(4).extra().asMap().get("status"));

        // talk：urgency 有值才进 extra
        Entry talk = all.get(5);
        assertEquals(ChatFeed.KIND_TALK, talk.kind());
        assertEquals("研发组", talk.group());
        assertEquals("张三", talk.fromName());
        assertEquals("dev_2", talk.toRoleId());
        assertEquals("李四", talk.toName());
        assertEquals("你好", talk.text());
        assertEquals("HIGH", talk.extra().asMap().get("urgency"));
        assertNull(all.get(6).extra().asMap().get("urgency"));

        // client：组长提问，收件人显示为 Client A
        Entry client = all.get(7);
        assertEquals(ChatFeed.KIND_CLIENT, client.kind());
        assertEquals("张三", client.fromName());
        assertEquals(ChatFeed.CLIENT_NAME, client.toName());
        assertEquals("客户要什么？", client.text());

        // system：System 发出
        Entry system = all.get(8);
        assertEquals(ChatFeed.KIND_SYSTEM, system.kind());
        assertEquals("System", system.fromName());
        assertEquals("", system.fromRoleId());
        assertEquals("系统提示", system.text());
    }

    @Test
    void 没有解析器时退化为roleId() {
        ChatFeed feed = new ChatFeed();
        feed.reasoning(DEV, "思考", meta(1));
        Entry entry = feed.since(0).get(0);
        assertEquals("dev_1", entry.fromRoleId());
        assertEquals("dev_1", entry.fromName(), "没有解析器时显示名退化为 roleId");
        assertEquals("", entry.group());
    }

    @Test
    void bindResolver可装配后补注入() {
        ChatFeed feed = new ChatFeed();
        feed.reasoning(DEV, "装配前的思考", meta(1));
        assertEquals("dev_1", feed.since(0).get(0).fromName());

        feed.bindResolver(id -> new ChatFeed.RoleInfo("张三", "研发组"));
        feed.reasoning(DEV, "装配后的思考", meta(1));
        assertEquals("张三", feed.since(0).get(1).fromName());
        assertEquals("研发组", feed.since(0).get(1).group());
    }

    @Test
    void 超过容量后淘汰最旧记录() {
        ChatFeed feed = new ChatFeed();
        for (int i = 0; i < ChatFeed.MAX_HISTORY + 1; i++) {
            feed.system("m" + i);
        }
        assertEquals(ChatFeed.MAX_HISTORY + 1, feed.watermark(), "watermark 仍单调增长（seq 不回收）");

        List<Entry> all = feed.since(0);
        assertEquals(ChatFeed.MAX_HISTORY, all.size(), "容量上限后只保留最新的 MAX_HISTORY 条");
        assertEquals(2L, all.get(0).seq(), "最旧的 seq=1 应被淘汰");
        assertEquals((long) ChatFeed.MAX_HISTORY + 1, all.get(all.size() - 1).seq());
        assertFalse(all.isEmpty());
    }

    @Test
    void Talk与Client空记录被忽略() {
        ChatFeed feed = new ChatFeed();
        feed.talk(null);
        feed.client(null);
        assertEquals(0L, feed.watermark());
    }
}
