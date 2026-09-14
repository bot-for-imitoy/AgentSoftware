package com.agent.software.adapters.input;

import com.agent.software.ports.InputPort;
import com.agent.software.adapters.web.ChatStore;

import java.time.Duration;

/**
 * Web-page implementation of {@link InputPort}: the question is surfaced through
 * the chat store and the browser's reply is read back.
 *
 * <p>The single-conversation lock lives in {@code ClientToolkit}; this adapter only
 * coordinates the pending-reply handshake on the store.
 */
public final class WebInputAdapter implements InputPort {

    private static final long DEFAULT_TIMEOUT_MS = 1_200_000L;

    private final ChatStore store;

    public WebInputAdapter(ChatStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    @Override
    public boolean interactive() {
        return store.isAttached();
    }

    @Override
    public ClientReply ask(ClientQuestion question, Duration timeout) {
        if (!store.isAttached()) {
            return ClientReply.unavailable("the Web UI is not attached "
                    + "(open the chat page and keep it open while a member waits for your reply)");
        }
        String error = store.beginClientWait(question.role().value(), question.askerName(), question.group());
        if (error != null) {
            return ClientReply.unavailable(error);
        }
        try {
            long timeoutMs = timeout == null ? DEFAULT_TIMEOUT_MS : Math.max(0, timeout.toMillis());
            String reply = store.awaitClientReply(timeoutMs);
            if (reply == null) {
                return ClientReply.unavailable("the client did not reply within "
                        + (timeoutMs / 1000) + "s; please try again later");
            }
            return ClientReply.answered(reply);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ClientReply.unavailable("interrupted while waiting for the client reply");
        } finally {
            store.endClientWait();
        }
    }
}
