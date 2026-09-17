package com.agent.software.kernel;

import com.agent.software.kernel.Ids.EventId;
import com.agent.software.kernel.Ids.MailId;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.ScheduleId;
import com.agent.software.kernel.Ids.TaskId;
import com.agent.software.sim.event.Priority;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 标识的非空校验、值语义与随机生成。 */
class IdsTest {

    @Test
    void 标识不能为空() {
        assertThrows(DomainError.class, () -> new RoleId(null));
        assertThrows(DomainError.class, () -> new RoleId(""));
        assertThrows(DomainError.class, () -> new RoleId("   "));
        assertThrows(DomainError.class, () -> new TaskId(null));
        assertThrows(DomainError.class, () -> new EventId(null));
        assertThrows(DomainError.class, () -> new ScheduleId(null));
        assertThrows(DomainError.class, () -> new MailId(null));
    }

    @Test
    void record提供值语义() {
        assertEquals(new RoleId("ceo"), new RoleId("ceo"));
        assertNotEquals(new RoleId("ceo"), new RoleId("coo"));
        assertEquals("ceo", new RoleId("ceo").value());
    }

    @Test
    void 生成的标识互不相同且长度一致() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(TaskId.generate().value());
        }
        assertEquals(200, seen.size(), "随机任务标识不应碰撞");
        assertEquals(12, TaskId.generate().value().length());
        assertEquals(12, EventId.generate().value().length());
        assertEquals(12, ScheduleId.generate().value().length());
    }

    @Test
    void 紧急度权重与反查() {
        assertEquals(1, Priority.LOW.weight());
        assertEquals(3, Priority.NORMAL.weight());
        assertEquals(6, Priority.HIGH.weight());
        assertEquals(10, Priority.EMERGENCY.weight());
        assertEquals(Priority.HIGH, Priority.ofWeight(6));
        assertEquals(Priority.NORMAL, Priority.ofWeight(999), "未知权重回退 NORMAL");
        assertEquals(Priority.EMERGENCY, Priority.parse("emergency"));
        assertEquals(Priority.NORMAL, Priority.parse("不存在的"));
    }
}
