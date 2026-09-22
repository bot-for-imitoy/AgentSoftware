package com.agent.software.core;

/**
 * 剩下的公共小工具。事件/状态/优先级已经分别下沉到 {@code event} 与 {@code role} 包，
 * 这里只保留"失败文本判定"这一条约定。
 */
public final class Types {

    private Types() {
    }

    /** 统一的失败文本前缀判定。 */
    public static boolean isFailureText(String s) {
        if (s == null) {
            return false;
        }
        return s.startsWith("[exit") || s.startsWith("Error")
                || s.startsWith("error") || s.startsWith("File not found")
                || s.startsWith("Directory not found");
    }
}
