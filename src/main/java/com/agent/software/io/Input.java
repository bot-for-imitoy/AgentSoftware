package com.agent.software.io;

/**
 * Client (user) input channel abstraction. Each {@link com.agent.software.AgentSystem} holds one
 * {@code Input} instance (injected at construction), deciding how a role obtains text input from the
 * client/user: console ({@link StdInput}) or Web page ({@link WebInput}).
 *
 * <p>Contract of {@link #read(String)}: it blocks until one line of client input is available on the
 * channel and returns it; {@code null} means the channel is closed/non-interactive; a string returned
 * by {@link #error(String)} means the read failed for a channel-specific reason (already human-readable
 * and prefixed), so callers can pass it through instead of treating it as client content.
 *
 * <p>The {@code target} parameter distinguishes different input boxes on the Web page (e.g. the group a
 * conversation belongs to, such as "Leadership Group"); the console standard stream needs no such
 * distinction, so {@link StdInput} ignores it.
 */
public abstract class Input {

    /** Prefix shared by all error strings returned by {@link #read(String)}. */
    public static final String ERROR_PREFIX = "talk_to_client: Error: ";

    /** Wraps a channel failure description into an error string recognizable by {@link #isError}. */
    public static String error(String detail) {
        return ERROR_PREFIX + detail;
    }

    /** Whether a {@link #read(String)} result is a channel error rather than client input. */
    public static boolean isError(String result) {
        return result != null && result.startsWith(ERROR_PREFIX);
    }

    /**
     * Whether this input channel is backed by the Web page. {@code false} by default (console-style
     * channels); {@link WebInput} returns {@code true}. Callers use this to decide how to announce the
     * question to the user (console prints it to stdout; the Web channel shows it through the chat store).
     */
    public boolean isWebPage() {
        return false;
    }

    /**
     * Blocks until the client/user submits one piece of input for the given {@code target}.
     *
     * @param target the input-box marker (e.g. the conversation group for the Web page); ignored by
     *               channels that have a single undifferentiated stream (console).
     * @return the entered text; {@code null} when the channel cannot provide input; an
     *         {@link #error(String) error string} when the read failed for a channel-specific reason.
     */
    public abstract String read(String target);

}
