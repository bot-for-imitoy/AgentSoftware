package com.agent.software.kernel;

/** The unified textual-failure convention used by computers and tools. */
public final class FailureText {

    private FailureText() {
    }

    /** Whether a tool/command result text signals a failure. */
    public static boolean isFailure(String s) {
        return s != null && (s.startsWith("[exit") || s.startsWith("Error") || s.startsWith("error")
                || s.startsWith("File not found") || s.startsWith("Directory not found"));
    }
}
