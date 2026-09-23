package com.agent.software.utils;

import java.util.List;
import java.util.Locale;

/**
 * 纯字符串处理工具。Toolkit 的命名转换放在这里，而不是作为 Toolkit 的成员方法。
 */
public final class Text {

    private Text() {
    }

    /** CamelCase → snake_case（TaskToolkit → task_toolkit，McpManager → mcp_manager）。 */
    public static String snakeCase(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && Character.isLowerCase(name.charAt(i - 1))) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 截断到 max 个字符，超出部分用 … 标记。 */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (max <= 0 || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    public static String joinNonBlank(String delimiter, List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (isBlank(p)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
