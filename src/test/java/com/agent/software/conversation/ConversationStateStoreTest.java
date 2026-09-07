package com.agent.software.conversation;

import com.agent.software.AgentSystem;
import com.agent.software.io.StdInput;
import com.agent.software.role.AgentRole;
import com.agent.software.store.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conversation persistence through the StateStore archive: an open day dialogue (role ↔ LLM API)
 * must survive a save/restore cycle together with the rest of the role state.
 */
class ConversationStateStoreTest {

    @TempDir
    Path tmp;

    private AgentSystem make(Path dataDir) {
        return new AgentSystem(dataDir, null, java.util.List.of("CEO"), 30.0, false, new StdInput());
    }

    @Test
    void openConversationSurvivesSaveRestoreRoundTrip() {
        StateStore store = new StateStore(tmp.resolve("state.json").toString());
        AgentSystem s1 = make(tmp.resolve("s1"));
        AgentRole ceo = s1.getRole("CEO");
        // simulate two completed daytime exchanges committed to the day dialogue (day 1)
        assertTrue(ceo.conversation().appendTaskExchange(1, "Collect the project requirements", "Requirements noted.", null));
        assertTrue(ceo.conversation().appendTaskExchange(1, "Reply to the client about the plan", "Plan sent to the client.", null));
        store.save(s1);

        AgentSystem s2 = make(tmp.resolve("s2"));
        assertEquals(1, store.restore(s2));
        AgentRole ceo2 = s2.getRole("CEO");
        Conversation conv2 = ceo2.conversation();
        assertEquals(4, conv2.historySize());
        assertEquals(1, conv2.day());
        Map<String, Object> dict = conv2.toDict();
        List<?> msgs = (List<?>) dict.get("messages");
        Map<?, ?> first = (Map<?, ?>) msgs.get(0);
        assertEquals("user", first.get("role"));
        assertEquals("Collect the project requirements", first.get("content"));
        Map<?, ?> third = (Map<?, ?>) msgs.get(2);
        assertEquals("user", third.get("role"));
        assertEquals("Reply to the client about the plan", third.get("content"));
        assertEquals("assistant", ((Map<?, ?>) msgs.get(3)).get("role"));
        assertEquals("Plan sent to the client.", ((Map<?, ?>) msgs.get(3)).get("content"));

        // the restored dialogue can continue (next task is prepared with the history)
        List<Map<String, Object>> prepared = conv2.prepareMessages("sys", "Next task", 1);
        assertEquals(6, prepared.size());
    }

    @Test
    void emptyConversationIsNotArchived() {
        StateStore store = new StateStore(tmp.resolve("state.json").toString());
        AgentSystem s1 = make(tmp.resolve("a1"));
        s1.getRole("CEO").conversation();   // touched but never used → empty
        store.save(s1);

        AgentSystem s2 = make(tmp.resolve("a2"));
        store.restore(s2);
        AgentRole ceo2 = s2.getRole("CEO");
        Conversation conv2 = ceo2.conversation();
        assertTrue(conv2.isEmpty());
        assertEquals(0, conv2.day());
        assertEquals(-1, conv2.closedDay());
        assertEquals(0, ((List<?>) conv2.toDict().get("messages")).size());
    }
}
