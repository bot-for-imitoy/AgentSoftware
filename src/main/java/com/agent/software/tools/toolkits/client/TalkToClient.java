package com.agent.software.tools.toolkits.client;

import com.agent.software.io.Input;
import com.agent.software.io.StdInput;
import com.agent.software.role.AgentRole;
import com.agent.software.tools.Tool;
import com.agent.software.tools.Toolkits;
import com.agent.software.web.ChatStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * talk_to_client — real-time communication with the client (user). Calling this tool pauses
 * and requests text input from the user; the entered content is returned as the result.
 * Used for: gathering requirements, confirming plans, reporting progress, raising questions.
 *
 * <p><b>Input channel</b>: the reply is read through the {@link Input} instance the role's
 * {@link com.agent.software.AgentSystem} holds ({@code AgentSystem.input}), so this tool no longer
 * reads {@code System.in} itself nor branches on Web attach state — each concrete Input decides how it
 * obtains the input:
 * <ul>
 *   <li>{@link StdInput} — console mode: blocks on {@code System.in}, the original behavior;</li>
 *   <li>{@link com.agent.software.io.WebInput} — Web mode: the message is recorded in the chat store
 *       (shown in the Web chat window), the input box is enabled automatically, and the client's reply
 *       typed on the page is returned; if no browser is attached it returns an error instead of
 *       blocking forever. The wait has a timeout ({@code AGENTSOFTWARE_CLIENT_REPLY_TIMEOUT},
 *       default 20 minutes).</li>
 * </ul>
 * The {@code target} passed to {@link Input#read(String)} is the conversation group (e.g. "Leadership
 * Group"), which distinguishes the input box on the Web page; the console stream needs no distinction.
 *
 * <p>Mutex: only one member may talk to the client at a time. If the lock is taken, an error is
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
            String group = agentRole != null && agentRole.group != null
                    && !agentRole.group.isBlank() ? agentRole.group : Toolkits.LEADERSHIP_GROUP;
            // Record the question to the chat store (shown in the Web UI; console mode keeps the log too)
            ChatStore store = chatStore();
            if (store != null) {
                store.record(ChatStore.KIND_CLIENT, group, roleId, name, "", ChatStore.CLIENT_NAME,
                        question.isEmpty() ? "(sent a message, please reply)" : question, null);
            }
            // The system's Input decides how the reply is read (console vs Web page).
            Input input = inputOf();
            // Console channels need the question announced on stdout (the Web page shows it via the store).
            if (!input.isWebPage()) {
                announceConsole(name, question);
            }
            // target = the conversation group: the input-box marker for the Web page (console ignores it)
            String reply = input.read(group);
            return formatReply(reply);
        } finally {
            lock.release(roleId);
        }
    }

    /** The Input of the owning AgentSystem; standalone roles fall back to console (StdInput). */
    private Input inputOf() {
        if (agentRole != null && agentRole.system() != null && agentRole.system().input != null) {
            return agentRole.system().input;
        }
        return new StdInput();
    }

    /** Console announcement of the question + reply prompt (kept from the historical console interaction). */
    private void announceConsole(String name, String question) {
        if (!question.isEmpty()) {
            System.out.println("\n  " + BOLD + "[" + name + "] " + question + RESET);
        } else {
            System.out.println("\n  " + BOLD + "[" + name + "] (sent a message, please reply)" + RESET);
        }
        System.out.print("  [Client A] Please enter your reply: ");
        System.out.flush();
    }

    /** Normalizes the raw read result into the tool result text. */
    private String formatReply(String raw) {
        if (raw == null) {
            return "talk_to_client: Error: cannot get user input (non-interactive environment).";
        }
        // Channel errors (e.g. Web UI not attached / reply timeout) pass through unchanged
        if (Input.isError(raw)) {
            return raw;
        }
        String reply = raw.strip();
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
