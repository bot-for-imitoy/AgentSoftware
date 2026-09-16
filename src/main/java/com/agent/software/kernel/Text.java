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
        throw new UnsupportedOperationException("skeleton");
    }

    /** 把任意连续空白折成单个空格并去掉首尾空白。 */
    public static String squashWhitespace(String s) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** null → ""。 */
    public static String orEmpty(String s) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** null / 空串 / 全空白 → true。 */
    public static boolean isBlank(String s) {
        throw new UnsupportedOperationException("skeleton");
    }

    /** 用分隔符连接非空白片段。 */
    public static String joinNonBlank(String separator, String... parts) {
        throw new UnsupportedOperationException("skeleton");
    }
}
