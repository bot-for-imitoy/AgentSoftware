package com.agent.software.app;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.LlmPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleSpecFactoryTest {

    static final class FakeLlm implements LlmPort {
        volatile String reply = "";
        volatile boolean fail;

        @Override
        public ChatReply chat(ChatRequest request) {
            return fail ? new ChatReply(API_ERROR_PREFIX + " boom", "", 0) : new ChatReply(reply, "", 10);
        }

        @Override
        public ChatReply summarize(String text, int maxTokens) {
            return new ChatReply("summary", "", 1);
        }

        @Override
        public ToolReply chatWithTools(ToolRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static RoleSpec existing(String id, String name) {
        return new RoleSpec(RoleId.of(id), name, id, 0, "Existing", "", "", List.of("a"),
                "", "", "", "podman", Payload.empty(), List.of("note"));
    }

    private static String roleJson(String roleId) {
        return "```json\n{\"role_id\":\"" + roleId + "\",\"title\":\"Rust Engineer\","
                + "\"responsibilities\":\"build services\",\"personality\":\"pragmatic\","
                + "\"skills\":[\"Rust\",\"gRPC\",\"PostgreSQL\",\"Docker\",\"K8s\"]}\n```";
    }

    @Test
    void createsARoleFromTheModelResponse() {
        FakeLlm llm = new FakeLlm();
        llm.reply = roleJson("rust_engineer");
        Supplier<List<RoleSpec>> roster = () -> List.of(existing("architect", "Wang Jianguo"));
        RoleSpecFactory factory = new RoleSpecFactory(llm, List.of("note", "talk"), roster);

        RoleSpec created = factory.create("need a rust engineer");

        assertEquals("rust_engineer", created.id().value());
        assertEquals("Rust Engineer", created.title());
        assertEquals(5, created.skills().size());
        assertEquals(List.of("note", "talk"), created.toolkits());
        assertTrue(created.name().length() > 0);
    }

    @Test
    void duplicateRoleIdGetsASuffix() {
        FakeLlm llm = new FakeLlm();
        llm.reply = roleJson("architect");
        RoleSpecFactory factory = new RoleSpecFactory(llm, List.of(), () -> List.of(existing("architect", "Wang")));

        RoleSpec created = factory.create("another architect");
        assertEquals("architect_1", created.id().value());
    }

    @Test
    void namesAreUnique() {
        FakeLlm llm = new FakeLlm();
        llm.reply = roleJson("a");
        RoleSpecFactory factory = new RoleSpecFactory(llm, List.of(), () -> List.of(existing("architect", "Wang Jianguo")));
        RoleSpec first = factory.create("x");
        llm.reply = roleJson("b");
        RoleSpec second = factory.create("y");
        assertNotEquals(first.name(), second.name());
    }

    @Test
    void failedOrUnparsableResponsesFailFast() {
        FakeLlm llm = new FakeLlm();
        llm.fail = true;
        RoleSpecFactory factory = new RoleSpecFactory(llm, List.of(), List::of);
        assertThrows(AgentException.PortException.class, () -> factory.create("x"));

        llm.fail = false;
        llm.reply = "I cannot help with that.";
        assertThrows(AgentException.PortException.class, () -> factory.create("x"));
    }

    @Test
    void parseJsonHandlesPlainAndFencedObjects() {
        Map<String, Object> plain = RoleSpecFactory.parseJson("{\"role_id\":\"x\"}");
        assertEquals("x", plain.get("role_id"));
        Map<String, Object> fenced = RoleSpecFactory.parseJson("```json\n{\"role_id\":\"y\"}\n```");
        assertEquals("y", fenced.get("role_id"));
        assertEquals(null, RoleSpecFactory.parseJson("no json here"));
    }
}
