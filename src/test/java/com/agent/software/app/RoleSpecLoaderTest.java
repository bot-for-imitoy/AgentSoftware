package com.agent.software.app;

import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.AgentException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleSpecLoaderTest {

    private static final List<String> DEFAULTS =
            List.of("memory", "note", "time", "todo", "pc", "email", "talk");

    private static RoleSpecLoader loader() {
        return RoleSpecLoader.fromClasspath(DEFAULTS);
    }

    @Test
    void loadsEveryBundledTemplate() {
        assertEquals(55, loader().ids().size());
    }

    @Test
    void leadershipRolesGetTheClientToolkit() {
        RoleSpec ceo = loader().require("CEO");
        assertEquals("Lin Zong", ceo.name());
        assertEquals("Leadership Group", ceo.group());
        assertTrue(ceo.toolkits().contains("client"));
        assertTrue(ceo.toolkits().contains("talk"));
    }

    @Test
    void hrGetsTheHrToolkit() {
        assertTrue(loader().require("HR").toolkits().contains("hr"));
    }

    @Test
    void ordinaryRolesGetDefaultsOnly() {
        RoleSpec architect = loader().require("architect");
        assertFalse(architect.toolkits().contains("client"));
        assertFalse(architect.toolkits().contains("hr"));
        assertTrue(architect.toolkits().contains("note"));
    }

    @Test
    void explicitToolkitListWins() {
        RoleSpecLoader explicit = RoleSpecLoader.fromJson(
                "{\"x\":{\"role_id\":\"x\",\"name\":\"X\",\"group\":\"Leadership Group\","
                        + "\"toolkits\":[\"a\",\"b\"]}}",
                List.of("fallback"));
        assertEquals(List.of("a", "b"), explicit.require("x").toolkits());
    }

    @Test
    void unknownRoleFailsFast() {
        assertThrows(AgentException.ConfigException.class, () -> loader().require("ghost"));
    }

    @Test
    void computerKindAndKwargsAreCarried() {
        RoleSpecLoader custom = RoleSpecLoader.fromJson(
                "{\"x\":{\"role_id\":\"x\",\"name\":\"X\",\"computer_kind\":\"local\","
                        + "\"computer_kwargs\":{\"base_dir\":\"/tmp/x\"}}}",
                DEFAULTS);
        RoleSpec spec = custom.require("x");
        assertEquals("local", spec.computerKind());
        assertEquals("/tmp/x", spec.computerKwargs().str("base_dir", ""));
    }
}
