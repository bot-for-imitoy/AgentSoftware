package com.agent.software.kernel;

import java.util.regex.Pattern;

/** Small naming helpers shared by file-backed adapters. */
public final class Names {

    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|#%\\s'`$;&]+");

    private Names() {
    }

    /** Turn arbitrary text into a safe file-name fragment (never empty). */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "untitled";
        }
        String cleaned = ILLEGAL.matcher(raw.strip()).replaceAll("_");
        return cleaned.isEmpty() ? "untitled" : cleaned;
    }

    /** Lower-case tool name: non {@code [a-z0-9_]} characters become underscores. */
    public static String toolName(String raw) {
        if (raw == null) {
            return "skill";
        }
        String name = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        name = name.replaceAll("^_+|_+$", "");
        return name.isEmpty() ? "skill" : name;
    }
}
