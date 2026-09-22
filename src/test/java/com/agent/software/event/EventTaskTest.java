package com.agent.software.event;

import com.agent.software.utils.DataRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Event / Task 的身份、构建、持久化与多态恢复。 */
class EventTaskTest {

    @Test
    void builderSetsFieldsAndUuidIdentity() {
        Event e = Event.builder()
                .from("CEO").to("COO").at(120)
                .type(EventType.TALK).priority(Priority.HIGH)
                .content("hello").source("talk")
                .build();
        assertEquals("CEO", e.fromRoleId);
        assertEquals("COO", e.targetRoleId);
        assertEquals(120, e.targetTime);
        assertEquals(EventType.TALK, e.type);
        assertEquals(Priority.HIGH, e.priority);
        assertEquals("hello", e.content);
        assertTrue(e.targetedAt("COO"));
        assertFalse(e.isBroadcast());
        assertFalse(e.isDue(119));
        assertTrue(e.isDue(120));
    }

    @Test
    void broadcastWhenNoTarget() {
        Event e = Event.builder().type(EventType.SHIFT_START).build();
        assertTrue(e.isBroadcast());
        assertTrue(e.targetedAt("anything") == false);
    }

    @Test
    void dataRoundTripKeepsIdentityAndPayload() {
        Event e = Event.builder().from("CEO").to("CTO").at(7)
                .type(EventType.NEW_MAIL).priority(Priority.NORMAL)
                .payload(Map.of("k", "v")).content("mail").build();
        Map<String, String> data = e.getData();

        Event restored = new Event(null, null, 0, "");
        restored.loadData(data);

        assertEquals(e.uuid, restored.uuid);
        assertEquals("CEO", restored.fromRoleId);
        assertEquals("CTO", restored.targetRoleId);
        assertEquals(7, restored.targetTime);
        assertEquals(EventType.NEW_MAIL, restored.type);
        assertEquals("v", restored.payload.get("k"));
        assertEquals(e, restored);   // UUIDObject 按 uuid 相等
    }

    @Test
    void taskIsAnEventAndRestoresThroughRegistry() {
        Task task = new Task("COO", "architect", 42, "build it", Priority.EMERGENCY);
        assertInstanceOf(Event.class, task);
        assertEquals(EventType.TASK, task.type);
        assertEquals(Task.PENDING, task.status);

        task.markRunning();
        task.markDone("done", 12);
        assertTrue(task.isFinished());
        assertEquals(12, task.tokensConsumed);

        var created = DataRegistry.create(Task.DATA_TYPE);
        assertInstanceOf(Task.class, created);
        created.loadData(task.getData());
        Task restored = (Task) created;
        assertEquals(task.uuid, restored.uuid);
        assertEquals(Task.DONE, restored.status);
        assertEquals("done", restored.result);
    }

    @Test
    void priorityParsing() {
        assertEquals(Priority.HIGH, Priority.from("high"));
        assertEquals(Priority.EMERGENCY, Priority.from(10));
        assertEquals(Priority.NORMAL, Priority.from("nonsense"));
        assertNotEquals(Priority.LOW.value, Priority.HIGH.value);
    }
}
