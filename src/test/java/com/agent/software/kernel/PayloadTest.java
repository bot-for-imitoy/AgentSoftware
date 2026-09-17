package com.agent.software.kernel;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Payload} 的类型化访问与不可变性。 */
class PayloadTest {

    @Test
    void 类型化读取() {
        Payload p = Payload.of("title", "开会").with("count", 3).with("urgent", true);
        assertEquals("开会", p.title());
        assertEquals("开会", p.stringOr("title", "x"));
        assertEquals("x", p.stringOr("missing", "x"));
        assertEquals(OptionalInt.of(3), p.intValue("count"));
        assertEquals(OptionalInt.empty(), p.intValue("missing"));
        assertTrue(p.boolOr("urgent", false));
        assertFalse(p.boolOr("missing", false));
        assertTrue(p.has("count"));
        assertFalse(p.has("nope"));
    }

    @Test
    void 数字以字符串给出也能读() {
        Payload p = Payload.of("n", "42");
        assertEquals(OptionalInt.of(42), p.intValue("n"));
        assertEquals(OptionalInt.empty(), Payload.of("n", "abc").intValue("n"));
    }

    @Test
    void 不可变且忽略空值() {
        Payload base = Payload.of("a", 1);
        Payload derived = base.with("b", 2);
        assertEquals(1, base.asMap().size(), "with 不应修改原对象");
        assertEquals(2, derived.asMap().size());
        assertThrows(UnsupportedOperationException.class, () -> derived.asMap().put("c", 3),
                "asMap 暴露的必须是不可变视图");
        assertEquals(0, Payload.of("null", null).asMap().size(), "null 值应被丢弃");
    }

    @Test
    void 由裸Map构造() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("subject", "周报");
        assertEquals("周报", Payload.ofMap(raw).subject());
        assertEquals(0, Payload.ofMap(null).asMap().size());
        assertEquals(0, Payload.empty().asMap().size());
    }
}
