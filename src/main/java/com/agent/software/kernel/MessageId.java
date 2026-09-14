package com.agent.software.kernel;

import java.util.UUID;

/** Strongly typed identifier for a mail message or chat message. */
public record MessageId(String value) {

    public MessageId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MessageId must not be blank");
        }
        value = value.strip();
    }

    public static MessageId of(String value) {
        return new MessageId(value);
    }

    public static MessageId newId() {
        return new MessageId(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    @Override
    public String toString() {
        return value;
    }
}
