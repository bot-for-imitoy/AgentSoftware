package com.agent.software.tools.toolkits.client;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;
import com.agent.software.tools.Toolkits;
import com.agent.software.web.ChatStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * talk_to_client — real-time communication with the client (user). Calling this tool pauses
 * and requests text input from the user; the entered content is returned as the result.
 * Used for: gathering requirements, confirming plans, reporting progress, raising questions.
 *
 * <p>Two input channels:
 * <ul>
 *   <li><b>Web mode</b>: when the Web UI is attached (inside the ChatStore heartbeat), the
 *       message is shown in the Web chat window, the input box is enabled automatically, and
 *       the client replies on the page → the result is returned. The wait has a timeout
 *       ({@code AGENTCOMPANY_CLIENT_REPLY_TIMEOUT}, default 20 minutes); on timeout an error is
 *       returned so the agent never blocks forever.</li>
 *   <li><b>Console mode</b>: when no Web UI is attached, the original behavior is kept, blocking
 *       on System.in.</li>
 * </ul>
 *
 * Mutex: only one member may talk to the client at a time. If the lock is taken, an error is
 * returned immediately without blocking, avoiding multiple people grabbing the input at once.
 */
public class TalkToClient extends Tool {

    private static final String BOLD = "\033[1m";
    private static final String RESET = "\033[0m";

    private final AgentRole agentRole;
    private final ClientCommunicationLock lock;

    public TalkToClient(AgentRole agentRole) {
        this(agentRole, ClientCommunicationLock.getInstance());
    }

    /** Package-private: tests can inject an independent lock instance. */
    TalkToClient(AgentRole agentRole, ClientCommunicationLock lock) {
        super();
        this.agentRole = agentRole;
        this.lock = lock;
    }

    @Override
    public String getToolName() {
        return "talk_to_client";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("message", "(Optional) What you want to say to the client (e.g. a question or progress report).");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        String roleId = agentRole != null ? agentRole.roleId : "";
        String name = agentRole != null ? agentRole.name : "Client";
        String conflict = lock.tryAcquire(roleId, name);
        if (conflict != null) {
            return "talk_to_client: Error: " + conflict + ", try again later.";
        }
        try {
            Object omsg = args.get("message");
            String question = omsg instanceof String s ? s.strip() : "";
            ChatStore store = chatStore();
            // Record the message to the chat store (shown in the Web UI; also logged in console mode)
            if (store != null) {
                String group = agentRole != null && agentRole.group != null
                        && !agentRole.group.isBlank() ? agentRole.group : Toolkits.LEADERSHIP_GROUP;
                store.record(ChatStore.KIND_CLIENT, group, roleId, name, "", ChatStore.CLIENT_NAME,
                        question.isEmpty() ? "(sent a message, please reply)" : question, null);
            }
            // Web mode: a Web UI is attached → wait for the client to reply on the page
            if (store != null && store.isAttached()) {
                return handlerWeb(store, roleId, name, question);
            }
            // Console mode (original behavior): block on System.in
            return handlerConsole(name, question);
        } finally {
            lock.release(roleId);
        }
    }

    /** Web mode: register the wait → block until the client replies on the page (with timeout). */
    private String handlerWeb(ChatStore store, String roleId, String name, String question) {
        String group = agentRole != null && agentRole.group != null
                && !agentRole.group.isBlank() ? agentRole.group : Toolkits.LEADERSHIP_GROUP;
        String beginErr = store.beginClientWait(roleId, name, group);
        if (beginErr != null) {
            return "talk_to_client: Error: " + beginErr + ", try again later.";
        }
        try {
            long timeoutMs = com.agent.software.web.ChatWebServer.replyTimeoutMs();
            String reply;
            try {
                reply = store.awaitClientReply(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "talk_to_client: Error: interrupted while waiting for the client reply.";
            }
            if (reply == null) {
                return "talk_to_client: Error: the client did not reply within "
                        + (timeoutMs / 1000) + "s (web interface). Please contact the client again later.";
            }
            return "talk_to_client: client reply: " + reply;
        } finally {
            store.endClientWait();
        }
    }

    /** Console mode: the original System.in interaction. */
    private String handlerConsole(String name, String question) {
        if (!question.isEmpty()) {
            System.out.println("\n  " + BOLD + "[" + name + "] " + question + RESET);
        } else {
            System.out.println("\n  " + BOLD + "[" + name + "] (sent a message, please reply)" + RESET);
        }
        System.out.print("  [Client A] Please enter your reply: ");
        System.out.flush();
        String reply;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            reply = reader.readLine();
        } catch (IOException e) {
            return "talk_to_client: Error: cannot get user input (non-interactive environment).";
        }
        if (reply == null) {
            return "talk_to_client: Error: cannot get user input (non-interactive environment).";
        }
        reply = reply.strip();
        if (reply.isEmpty()) {
            return "talk_to_client: the client did not enter anything (empty reply).";
        }
        return "talk_to_client: client reply: " + reply;
    }

    /** The chat store of the system this role belongs to (null for standalone roles not bound to a system). */
    private ChatStore chatStore() {
        return agentRole != null && agentRole.system() != null
                ? agentRole.system().chatStore : null;
    }
}
