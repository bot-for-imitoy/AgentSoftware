package com.agent.software.infra.json;

import com.agent.software.kernel.DayTick;
import com.agent.software.kernel.Payload;
import com.agent.software.agent.task.Task;
import com.agent.software.llm.Message;
import com.agent.software.llm.ToolCallRequest;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON 编解码：record、Payload、时间类型与持久化形状的往返。 */
class JacksonJsonCodecTest {

    private final JacksonJsonCodec json = new JacksonJsonCodec();

    @Test
    void 基本类型往返() {
        assertEquals("{\"a\":1}", json.write(Map.of("a", 1)));
        Map<String, Object> parsed = json.readMap("{\"a\":1,\"b\":\"x\"}");
        assertEquals(1, parsed.get("a"));
        assertEquals("x", parsed.get("b"));
        assertEquals(0, json.readMap("  ").size());
        assertThrows(com.agent.software.kernel.DomainError.class, () -> json.readMap("{不是 json}"));
        assertEquals(0, json.tryReadMap("{不是 json}").size(), "容错读取失败应返回空 Map");
    }

    @Test
    void Payload有自定义的磁盘形状() {
        Payload payload = Payload.of("title", "开会").with("count", 2);
        String text = json.write(payload);
        assertTrue(text.contains("\"title\""), "Payload 应序列化成普通对象，实际: " + text);
        Payload back = json.read(text, Payload.class);
        assertEquals("开会", back.title());
        assertEquals(2, back.intValue("count").orElseThrow());
    }

    @Test
    void 时间类型写成ISO字符串() {
        assertEquals("\"2026-09-17\"", json.write(LocalDate.of(2026, 9, 17)));
        assertEquals(LocalDate.of(2026, 9, 17), json.read("\"2026-09-17\"", LocalDate.class));
        Instant instant = Instant.parse("2026-09-17T08:00:00Z");
        assertEquals(instant, json.read(json.write(instant), Instant.class));
    }

    @Test
    void 任务的持久化记录可以往返() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("event_id", "abc");
        context.put("payload", Map.of("title", "开会"));
        Task.TaskRecord record = new Task.TaskRecord("t1", 6, "[time/SHIFT_START] 上班",
                EventKind.SHIFT_START.wire(), context, "DONE", "已完成", 42,
                1_760_000_000.0, "ceo");

        Task.TaskRecord back = json.read(json.write(record), Task.TaskRecord.class);
        assertEquals(record.id(), back.id());
        assertEquals(record.urgency(), back.urgency());
        assertEquals(record.source(), back.source());
        assertEquals(record.status(), back.status());
        assertEquals(record.tokens(), back.tokens());
        assertEquals(record.createdAt(), back.createdAt(), 0.001);
        assertEquals(record.assignee(), back.assignee());
        assertNotNull(back.context());
        assertEquals("abc", back.context().get("event_id"));
    }

    @Test
    void 对话消息可以往返() {
        Message message = Message.assistant("我来处理", List.of(
                new ToolCallRequest("call-1", "summary", Payload.of("content", "今天做完了"))));
        Message back = json.read(json.write(message), Message.class);
        assertEquals(Message.Role.ASSISTANT, back.role());
        assertEquals("我来处理", back.content());
        assertEquals(1, back.toolCalls().size());
        assertEquals("summary", back.toolCalls().get(0).toolName());
        assertEquals("今天做完了", back.toolCalls().get(0).arguments().stringOr("content", ""));

        Message tool = Message.tool("call-1", "ok");
        Message toolBack = json.read(json.write(tool), Message.class);
        assertEquals(Message.Role.TOOL, toolBack.role());
        assertEquals("call-1", toolBack.toolCallId());
    }

    @Test
    void DayTick与枚举往返() {
        assertEquals(new DayTick(3, 120), json.read(json.write(new DayTick(3, 120)), DayTick.class));
        assertEquals(Priority.HIGH, json.read(json.write(Priority.HIGH), Priority.class));
        assertEquals(EventKind.SHIFT_END, json.read(json.write(EventKind.SHIFT_END), EventKind.class));
    }
}
