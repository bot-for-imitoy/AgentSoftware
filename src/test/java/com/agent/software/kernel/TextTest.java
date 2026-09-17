package com.agent.software.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Text} 的纯函数语义。 */
class TextTest {

    @Test
    void 截断与空值() {
        assertEquals("abc", Text.truncate("abcdef", 3));
        assertEquals("abcdef", Text.truncate("abcdef", 10));
        assertEquals("", Text.truncate(null, 5));
        assertEquals("", Text.truncate("abc", 0));
        assertEquals("", Text.truncate("abc", -1));
    }

    @Test
    void 空白归一化() {
        assertEquals("a b c", Text.squashWhitespace("  a \n\t b   c  "));
        assertEquals("", Text.squashWhitespace(null));
        assertTrue(Text.isBlank("   \n "));
        assertTrue(Text.isBlank(null));
        assertFalse(Text.isBlank(" x "));
    }

    @Test
    void 非空白拼接() {
        assertEquals("a-b", Text.joinNonBlank("-", "a", null, "  ", "b"));
        assertEquals("", Text.joinNonBlank("-"));
        assertEquals("x", Text.joinNonBlank("-", "", "x", ""));
        assertEquals("", Text.orEmpty(null));
    }
}
