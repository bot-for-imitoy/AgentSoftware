package com.agent.software.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadTest {

    @Test
    void typedAccessorsConvertStringsAndNumbers() {
        Payload p = Payload.of(new LinkedHashMap<>(Map.of(
                "text", "hello",
                "count", 3,
                "ratio", 1.5,
                "flag", "yes")));
        assertEquals("hello", p.str("text", ""));
        assertEquals("hello", p.str("text").orElseThrow());
        assertEquals(3, p.intVal("count", 0));
        assertEquals(3, p.intVal("count").orElseThrow());
        assertEquals(1.5, p.doubleVal("ratio", 0.0));
        assertTrue(p.boolVal("flag", false));
    }

    @Test
    void missingKeysUseDefaults() {
        Payload p = Payload.empty();
        assertTrue(p.isEmpty());
        assertEquals("d", p.str("x", "d"));
        assertEquals(5, p.intVal("x", 5));
        assertEquals(0.0, p.doubleVal("x", 0.0));
        assertFalse(p.boolVal("x", false));
    }

    @Test
    void withAndMergedProduceNewInstances() {
        Payload base = Payload.of("a", 1);
        Payload extended = base.with("b", 2);
        assertEquals(1, base.asMap().size());
        assertEquals(2, extended.intVal("b", 0));
        Payload merged = base.mergedWith(Payload.of("a", 10));
        assertEquals(10, merged.intVal("a", 0));
    }

    @Test
    void payloadIsImmutable() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("k", "v");
        Payload p = Payload.of(src);
        src.put("k", "changed");
        assertEquals("v", p.str("k", ""));
        assertThrows(UnsupportedOperationException.class, () -> p.asMap().put("x", 1));
    }
}
