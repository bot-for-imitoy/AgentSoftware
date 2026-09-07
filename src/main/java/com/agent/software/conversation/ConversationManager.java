package com.agent.software.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversation manager (role ↔ LLM API conversation registry).
 *
 * <p>Holds one {@link Conversation} per role. Each {@link com.agent.software.AgentSystem} owns its
 * own manager instance (exactly like its MailService / ChatStore / MCP manager), so multiple
 * AgentSystems in one process keep their dialogues fully isolated; roles not bound to a system
 * (standalone demo pools) fall back to the process-level default instance.
 */
public final class ConversationManager {

    /** Process-level default manager (used by standalone roles not bound to an AgentSystem). */
    private static final ConversationManager DEFAULT = new ConversationManager();

    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();

    public static ConversationManager getDefault() {
        return DEFAULT;
    }

    /** Stable key of a role's conversation (role_id when present, otherwise the person name). */
    public static String keyFor(String roleId, String name) {
        String key = roleId;
        if (key == null || key.isEmpty()) {
            key = name;
        }
        return key == null || key.isEmpty() ? "agent" : key;
    }

    /** Get (or lazily create) the conversation of the given role. */
    public Conversation forRole(String roleId) {
        return conversations.computeIfAbsent(roleId, Conversation::new);
    }

    /** Get the conversation of the role if one was already created; null otherwise. */
    public Conversation getIfPresent(String roleId) {
        return conversations.get(roleId);
    }

    /** Number of conversations registered so far. */
    public int activeCount() {
        return conversations.size();
    }

    /** Snapshot of all registered conversations (for stats / tests). */
    public List<Conversation> all() {
        return new ArrayList<>(conversations.values());
    }

    /** Drop all conversations (mainly a test hook; production conversations follow their role lifecycle). */
    public void clear() {
        conversations.clear();
    }
}
