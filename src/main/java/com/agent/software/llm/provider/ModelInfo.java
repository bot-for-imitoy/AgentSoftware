package com.agent.software.llm.provider;

import java.util.Map;

/**
 * One model returned by a provider's model list endpoint ({@code GET /models}),
 * normalized across the OpenAI and Anthropic response dialects.
 *
 * <p>Field mapping from the raw JSON entry:
 * <ul>
 *   <li>OpenAI dialect: {@code id} / {@code owned_by} / {@code created} (epoch seconds);
 *       display name is usually absent.</li>
 *   <li>Anthropic dialect: {@code id} / {@code display_name} / {@code created_at}
 *       (ISO-8601 timestamp); {@code owned_by} is absent.</li>
 * </ul>
 */
public final class ModelInfo {

    private final String id;
    private final String displayName;
    private final Long createdAtMillis;   // epoch millis, null when the source carries no timestamp
    private final String ownedBy;
    private final Map<String, Object> raw; // the original JSON entry, for callers that need custom fields

    public ModelInfo(String id, String displayName, Long createdAtMillis,
                     String ownedBy, Map<String, Object> raw) {
        this.id = id;
        this.displayName = displayName == null || displayName.isEmpty() ? id : displayName;
        this.createdAtMillis = createdAtMillis;
        this.ownedBy = ownedBy == null ? "" : ownedBy;
        this.raw = raw != null ? Map.copyOf(raw) : Map.of();
    }

    /** Model id as used in chat requests (e.g. "gpt-4o-mini", "claude-sonnet-4-20250514"). */
    public String id() {
        return id;
    }

    /** Human friendly model name (falls back to the id when the source has none). */
    public String displayName() {
        return displayName;
    }

    /** Creation time in epoch millis, or null when the source does not report one. */
    public Long createdAtMillis() {
        return createdAtMillis;
    }

    /** Owning organization from the OpenAI dialect, empty otherwise. */
    public String ownedBy() {
        return ownedBy;
    }

    /** The original JSON entry of this model, for custom provider fields. */
    public Map<String, Object> raw() {
        return raw;
    }

    @Override
    public String toString() {
        return "ModelInfo{id='" + id + "', displayName='" + displayName + "'}";
    }
}
