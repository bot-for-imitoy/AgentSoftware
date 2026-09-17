package com.agent.software.kernel;

/**
 * 纯文本工具：截断、空白归一化、空判定。
 *
 * <p>目的：消灭 master 各处重复的 private {@code truncate(String,int)} / 归一化片段，
 * 让"展示用的截断"只有一个实现。
 */
public final class Text {

    private Text() {
    }

    /** 截断到 max 个字符（null 视为空串）。 */
    public static String truncate(String s, int max) {
        String v = orEmpty(s);
        if (max <= 0) {
            return "";
        }
        return v.length() <= max ? v : v.substring(0, max);
    }

    /** 把任意连续空白折成单个空格并去掉首尾空白。 */
    public static String squashWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    /** null → ""。 */
    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** null / 空串 / 全空白 → true。 */
    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 用分隔符连接非空白片段。 */
    public static String joinNonBlank(String separator, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (isBlank(part)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(separator);
            }
            sb.append(part);
        }
        return sb.toString();
    }
}
