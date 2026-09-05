package com.agent.software.io;

import com.agent.software.tools.toolkits.client.ClientCommunicationLock;
import com.agent.software.web.ChatStore;
import com.agent.software.web.ChatWebServer;

/**
 * Web-page input channel: reads the client's reply typed into the Web UI input box.
 *
 * <p>{@link #read(String)} registers a pending "waiting for the client reply" state on the system's
 * {@link ChatStore} (which enables the input box in the Web frontend and shows who is waiting), then
 * blocks until the client submits a reply on the page ({@code POST /api/reply} → {@code
 * ChatStore.postClientReply}). The entered text is returned to the caller.
 *
 * <p>The {@code target} parameter identifies which input box / conversation on the page is being read:
 * for the client dialogue it is the conversation group (e.g. "Leadership Group") that the question
 * belongs to. The console standard stream needs no such distinction, so {@link StdInput} ignores it.
 *
 * <p>This is a pure Web channel: when the Web UI is not attached (no browser heartbeat within the
 * {@link ChatStore#ATTACH_TTL_MS} window), {@link #read(String)} returns an {@link Input#error(String)
 * error string} instead of blocking forever — console input for headless runs is provided by
 * {@link StdInput}.
 *
 * <p>The requester identity (who is waiting) is taken from the {@link ClientCommunicationLock} the
 * system owns: {@code talk_to_client} acquires it before calling {@code read}, so the lock holder is
 * the member awaiting the client reply. The instance is bound to a system's store + lock by
 * {@code AgentSystem} at construction (see {@code AgentSystem#AgentSystem}).
 */
public class WebInput extends Input {

    private ChatStore store;
    private ClientCommunicationLock clientLock;

    /** Creates an unbound Web input; bind it via {@link #bind(ChatStore, ClientCommunicationLock)}. */
    public WebInput() {
    }

    /** Creates a Web input already bound to a chat store + client-communication lock. */
    public WebInput(ChatStore store, ClientCommunicationLock clientLock) {
        this.store = store;
        this.clientLock = clientLock;
    }

    /** Binds this input to the owning system's chat store + client-communication lock. */
    public void bind(ChatStore store, ClientCommunicationLock clientLock) {
        this.store = store;
        this.clientLock = clientLock;
    }

    @Override
    public boolean isWebPage() {
        return true;
    }

    @Override
    public String read(String target) {
        if (store == null || clientLock == null) {
            return Input.error("cannot get user input: web input is not bound to an AgentSystem chat store.");
        }
        if (target == null || target.isBlank()) {
            return Input.error("cannot get user input: missing input-box target (conversation group).");
        }
        if (!store.isAttached()) {
            return Input.error("cannot get user input: the Web UI is not attached "
                    + "(no browser heartbeat; open the chat page and keep it open while a member waits for your reply).");
        }
        // The client-communication lock holder is the member waiting for the reply (talk_to_client
        // acquires the lock before calling read); it identifies the pending conversation for the page.
        String roleId = clientLock.holderRoleId();
        String name = clientLock.holderName();
        if (roleId == null) {
            return Input.error("cannot get user input: no client conversation is active (communication lock not held).");
        }
        String beginErr = store.beginClientWait(roleId, name, target);
        if (beginErr != null) {
            return Input.error(beginErr);
        }
        try {
            long timeoutMs = ChatWebServer.replyTimeoutMs();
            String reply;
            try {
                reply = store.awaitClientReply(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Input.error("interrupted while waiting for the client reply.");
            }
            if (reply == null) {
                return Input.error("the client did not reply within "
                        + (timeoutMs / 1000) + "s (web interface). Please contact the client again later.");
            }
            return reply;
        } finally {
            store.endClientWait();
        }
    }

}
