package com.agent.software.role;

import com.agent.software.event.Event;
import com.agent.software.event.EventType;
import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 角色事件队列：优先级出队、同优先级 FIFO、状态契约。 */
class RoleQueueTest {

    private static Role role() {
        Employee e = new Employee("coder", "Zhang San", "Frontend Development Group");
        e.template.put("title", "Frontend Engineer");
        e.template.put("username", "zhangsan");
        e.template.put("skills", "[\"java\",\"react\"]");
        return new Role(e);
    }

    @Test
    void employeeTemplateIsApplied() {
        Role r = role();
        assertEquals("coder", r.roleId);
        assertEquals("Zhang San", r.name);
        assertEquals("Frontend Engineer", r.title);       // 不带 JSON 引号
        assertEquals(2, r.skills.size());
        assertEquals(RoleState.IDLE, r.getState());
    }

    @Test
    void higherPriorityIsPoppedFirst() throws Exception {
        Role r = role();
        r.enqueue(Event.builder().at(1).type(EventType.TASK).priority(Priority.LOW).content("low").build());
        r.enqueue(Event.builder().at(2).type(EventType.TASK).priority(Priority.EMERGENCY).content("urgent").build());
        r.enqueue(Event.builder().at(3).type(EventType.TASK).priority(Priority.NORMAL).content("normal").build());

        assertEquals(3, r.queueDepth());
        assertEquals("urgent", r.pollEvent(1).content);
        assertEquals("normal", r.pollEvent(1).content);
        assertEquals("low", r.pollEvent(1).content);
        assertNull(r.pollEvent(1));
        assertEquals(0, r.queueDepth());
    }

    @Test
    void samePriorityIsFifo() throws Exception {
        Role r = role();
        r.enqueue(new Task("a", "coder", 1, "first", Priority.NORMAL));
        r.enqueue(new Task("b", "coder", 2, "second", Priority.NORMAL));
        assertEquals("first", r.pollEvent(1).content);
        assertEquals("second", r.pollEvent(1).content);
    }

    @Test
    void peekDoesNotRemove() {
        Role r = role();
        r.enqueue(Event.builder().type(EventType.TALK).priority(Priority.HIGH).content("hello").build());
        assertEquals("hello", r.peekEvent().content);
        assertEquals(1, r.queueDepth());
    }

    @Test
    void computerAndContextAreNotLazilyCreated() {
        Role r = role();
        assertTrue(!r.hasComputer());
        assertThrows(IllegalStateException.class, r::getComputer);
        assertThrows(IllegalStateException.class, r::getContext);
        assertThrows(IllegalStateException.class, r::getLlm);
    }
}
