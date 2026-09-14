package com.agent.software.ports;

import com.agent.software.kernel.RoleId;

import java.time.Duration;

/**
 * Client (human user) input channel.
 *
 * <p>One implementation reads the console, another the Web page; the runtime and
 * the {@code talk_to_client} tool no longer branch on channel type.
 */
public interface InputPort {

    /** Whether the channel can actually obtain input right now. */
    boolean interactive();

    /** Ask the client a question and block up to {@code timeout} for a reply. */
    ClientReply ask(ClientQuestion question, Duration timeout);

    record ClientQuestion(RoleId role, String askerName, String group, String text) {
        public ClientQuestion {
            text = text == null ? "" : text;
            askerName = askerName == null ? "" : askerName;
            group = group == null ? "" : group;
        }
    }

    record ClientReply(boolean answered, String text, String error) {
        public ClientReply {
            text = text == null ? "" : text;
            error = error == null ? "" : error;
        }

        public static ClientReply answered(String text) {
            return new ClientReply(true, text, "");
        }

        public static ClientReply unavailable(String error) {
            return new ClientReply(false, "", error);
        }
    }
}
