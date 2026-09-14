package com.agent.software.tools.spi;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.AgentException;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolServiceTest {

    private static final RoleId ROLE = RoleId.of("ceo");

    private static Tool echo() {
        return Tools.of("echo", "echo text",
                JsonSchema.builder()
                        .required("text", JsonSchema.Property.string("text"))
                        .build(),
                (role, call) -> ToolResult.success("echo:" + Tools.arg(call, "text")));
    }

    private static RoleSpec spec(List<String> toolkits) {
        return new RoleSpec(RoleId.of("ceo"), "Lin Zong", "linzong", 1101, "CEO", "", "",
                List.of(), "", "Leadership Group", "", "local", Payload.empty(), toolkits);
    }

    @Test
    void bindsSpecsAndInvokes() {
        ToolService service = new ToolService();
        service.bind(ROLE, List.of(new Toolkit("t", "test", List.of(echo()))));

        assertEquals(1, service.boundRoleCount());
        assertEquals(1, service.specs(ROLE).size());
        assertEquals("echo", service.specs(ROLE).get(0).name());

        ToolResult result = service.invoke(ROLE, ToolCall.of("echo", Payload.of("text", "hi")));
        assertTrue(result.ok());
        assertEquals("echo:hi", result.text());
    }

    @Test
    void unknownToolAndUnboundRoleReturnErrors() {
        ToolService service = new ToolService();
        service.bind(ROLE, List.of(new Toolkit("t", "test", List.of(echo()))));

        assertFalse(service.invoke(ROLE, ToolCall.of("ghost", Payload.empty())).ok());
        assertFalse(service.invoke(RoleId.of("nobody"), ToolCall.of("echo", Payload.empty())).ok());
    }

    @Test
    void handlerExceptionBecomesErrorResult() {
        Tool boom = Tools.of("boom", "", JsonSchema.object(), (role, call) -> {
            throw new IllegalStateException("kaboom");
        });
        ToolService service = new ToolService();
        service.bind(ROLE, List.of(new Toolkit("t", "test", List.of(boom))));

        ToolResult result = service.invoke(ROLE, ToolCall.of("boom", Payload.empty()));
        assertFalse(result.ok());
        assertTrue(result.text().contains("kaboom"));
    }

    @Test
    void catalogRejectsUnknownToolkitId() {
        ToolkitCatalog catalog = new ToolkitCatalog()
                .register(new Toolkit("todo", "todos", List.of(echo())));
        assertThrows(AgentException.ConfigException.class,
                () -> catalog.forSpec(spec(List.of("todo", "ghost"))));
    }

    @Test
    void catalogReturnsDeclaredOrderAndUnbinds() {
        ToolkitCatalog catalog = new ToolkitCatalog()
                .register(new Toolkit("a", "a", List.of(echo())))
                .register(new Toolkit("b", "b", List.of(echo())));
        assertEquals(List.of("b", "a"), catalog.forSpec(spec(List.of("b", "a"))).stream()
                .map(Toolkit::id).toList());

        ToolService service = new ToolService();
        service.bind(ROLE, catalog.forSpec(spec(List.of("a"))));
        service.unbind(ROLE);
        assertTrue(service.specs(ROLE).isEmpty());
    }
}
