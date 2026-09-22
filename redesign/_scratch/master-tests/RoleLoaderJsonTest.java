package com.agent.software.role;

import com.agent.software.core.Types;
import com.agent.software.utils.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoleLoader JSON tests: the built-in role_templates.json resource and the JSON → AgentRole loader.
 */
class RoleLoaderJsonTest {

    @TempDir
    Path tmp;

    // ── Built-in resource ──────────────────────────────────────

    /** The built-in JSON resource must fully restore the 55 role templates, with all default roles present. */
    @Test
    void testResourceLoadsAllTemplates() {
        assertEquals(55, RoleLoader.TEMPLATES.size(),
                "built-in template count should be 55 (role_templates.json)");
        for (String rid : RoleLoader.DEFAULT_ROLES) {
            assertTrue(RoleLoader.TEMPLATES.containsKey(rid), "default roles missing: " + rid);
        }
    }

    /** The resource JSON file itself is complete: required fields non-empty, role_id matches the key, skills/keywords non-empty. */
    @SuppressWarnings("unchecked")
    @Test
    void testResourceJsonComplete() throws IOException {
        String resource = new String(
                RoleLoader.class.getClassLoader().getResourceAsStream(
                        RoleLoader.DEFAULT_TEMPLATES_RESOURCE).readAllBytes(),
                StandardCharsets.UTF_8);
        Map<String, Object> root = Json.parseObject(resource);
        assertEquals(55, root.size());
        for (Map.Entry<String, Object> e : root.entrySet()) {
            String id = e.getKey();
            Map<String, Object> conf = (Map<String, Object>) e.getValue();
            assertNotNull(conf.get("name"), id + " missing name");
            // username: every template gives an explicit pinyin username (replacing the old PinyinMap); valid format is lowercase ASCII
            String username = Json.str(conf, "username", "");
            assertTrue(username.matches("[a-z0-9_]+"), id + " has invalid or missing username '" + username + "'");
            assertNotNull(conf.get("title"), id + " missing title");
            assertNotNull(conf.get("responsibilities"), id + " missing responsibilities");
            assertNotNull(conf.get("personality"), id + " missing personality");
            assertTrue(Json.strList(conf, "skills").size() > 0, id + " has empty skills");
            assertTrue(Json.strList(conf, "interest_keywords").size() > 0,
                    id + " has empty interest_keywords");
            assertEquals(id, Json.str(conf, "role_id", ""), id + " role_id does not match the key");
        }
    }

    // ── Registry spot checks ──────────────────────────────────

    @Test
    void testTemplateSpotChecks() {
        AgentRole architect = RoleLoader.getTemplate("architect");
        assertEquals("Wang Jianguo", architect.name);
        assertEquals("System Architect", architect.title);
        assertEquals("Architecture & Release Group", architect.group);
        assertFalse(architect.isDefault);
        assertTrue(architect.skills.contains("C4 Model"));
        assertTrue(architect.interestKeywords.contains("architecture"));

        AgentRole ceo = RoleLoader.getTemplate("CEO");
        assertEquals("Lin Zong", ceo.name);
        assertTrue(ceo.isDefault);
        assertEquals("Leadership Group", ceo.group);

        AgentRole rm = RoleLoader.getTemplate("release_manager");
        assertTrue(rm.systemPromptExtra.contains("/mnt/drive/Public/work/"));
        assertTrue(rm.systemPromptExtra.contains("git init"));

        AgentRole lead = RoleLoader.getTemplate("frontend_lead");
        assertTrue(lead.systemPromptExtra.contains("Fang Jinyan"));
        assertTrue(lead.systemPromptExtra.contains("review"));

        AgentRole tester = RoleLoader.getTemplate("tester_20");
        assertEquals("Ruan Zhiming", tester.name);
        assertEquals("Testing Group", tester.group);
    }

    /** Only management roles (CEO/COO/HR/CFO) are marked is_default. */
    @Test
    void testOnlyManagementRolesAreDefault() {
        Set<String> defaultIds = Set.of("CEO", "COO", "HR", "CFO");
        for (Map.Entry<String, Supplier<AgentRole>> e : RoleLoader.TEMPLATES.entrySet()) {
            assertEquals(defaultIds.contains(e.getKey()),
                    e.getValue().get().isDefault, e.getKey() + " has incorrect is_default");
        }
    }

    // ── JSON → AgentRole loader ──────────────────────────────

    /** Shape ②: JSON array → list of role objects; each load produces independent copies. */
    @Test
    void testLoadFromJsonListForm() throws IOException {
        String json = """
                [
                  {
                    "role_id": "alpha",
                    "name": "Zhang San",
                    "title": "Test Role",
                    "responsibilities": "Responsible for testing",
                    "personality": "Meticulous and responsible",
                    "skills": ["testing", "quality"],
                    "interest_keywords": ["testing", "quality"],
                    "system_prompt_extra": "Keep output concise",
                    "group": "Testing Group"
                  },
                  {
                    "role_id": "beta",
                    "name": "Li Si",
                    "title": "Development Role",
                    "responsibilities": "Responsible for development",
                    "personality": "Pragmatic",
                    "skills": ["Java"],
                    "interest_keywords": ["development"]
                  }
                ]
                """;
        List<AgentRole> roles = RoleLoader.loadFromJson(json);
        assertEquals(2, roles.size());
        AgentRole a = roles.get(0);
        assertEquals("alpha", a.roleId);
        assertEquals("Zhang San", a.name);
        assertEquals("Testing Group", a.group);
        assertTrue(a.skills.contains("testing"));
        assertTrue(a.interestKeywords.contains("quality"));
        assertEquals("Keep output concise", a.systemPromptExtra);
        AgentRole b = roles.get(1);
        assertEquals("beta", b.roleId);
        assertFalse(b.isDefault);
        assertEquals("", b.systemPromptExtra);  // extra not given → default empty string
        // independent copies: modifying one does not affect the other
        roles.get(0).skills.add("new skill");
        assertFalse(roles.get(1).skills.contains("new skill"));
    }

    /** Shape ①: role_id → config map → factory table; each factory call returns an independent copy. */
    @Test
    void testTemplatesFromJsonMapForm() throws IOException {
        String json = """
                {
                  "alpha": {
                    "name": "Zhang San",
                    "title": "Test Role",
                    "responsibilities": "Responsible for testing",
                    "personality": "Meticulous and responsible",
                    "skills": ["testing"],
                    "interest_keywords": ["testing"]
                  }
                }
                """;
        Map<String, Supplier<AgentRole>> table = RoleLoader.templatesFromJson(json);
        assertEquals(Set.of("alpha"), table.keySet());
        assertEquals("alpha", table.get("alpha").get().roleId);
        assertEquals("Zhang San", table.get("alpha").get().name);
        assertNotSame(table.get("alpha").get(), table.get("alpha").get());
    }

    /** Shape ③: a single role object. */
    @Test
    void testLoadFromJsonSingleObjectForm() throws IOException {
        String json = """
                {
                  "role_id": "solo",
                  "name": "Standalone Role",
                  "title": "Solo",
                  "responsibilities": "x",
                  "personality": "y",
                  "skills": ["a"],
                  "interest_keywords": ["b"]
                }
                """;
        List<AgentRole> roles = RoleLoader.loadFromJson(json);
        assertEquals(1, roles.size());
        assertEquals("solo", roles.get(0).roleId);
        assertEquals("Standalone Role", roles.get(0).name);
    }

    /** Loading from a JSON file. */
    @Test
    void testLoadFromJsonFile() throws IOException {
        Path f = tmp.resolve("roles.json");
        Files.writeString(f, """
                {
                  "file_role": {
                    "name": "File Role",
                    "title": "From File",
                    "responsibilities": "x",
                    "personality": "y",
                    "skills": ["s"],
                    "interest_keywords": ["k"]
                  }
                }
                """, StandardCharsets.UTF_8);
        List<AgentRole> roles = RoleLoader.loadFromJson(f);
        assertEquals(1, roles.size());
        assertEquals("file_role", roles.get(0).roleId);
    }

    /** fromJsonMap covers all AgentRole configurable fields (incl. username/uid/state/threshold/computer). */
    @Test
    void testFromJsonMapAllFields() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role_id", "rich");
        m.put("name", "Rich Fields");
        m.put("username", "fuziduan");
        m.put("uid", 1500);
        m.put("title", "T");
        m.put("responsibilities", "R");
        m.put("personality", "P");
        m.put("skills", List.of("s1", "s2"));
        m.put("interest_keywords", List.of("k1", "k2"));
        m.put("system_prompt_extra", "E");
        m.put("is_default", true);
        m.put("group", "G");
        m.put("email", "rich@company.com");
        m.put("computer_kind", "local");
        m.put("computer_kwargs", Map.of("cpu", "2"));
        m.put("salience_threshold", 0.6);
        m.put("state", "WAIT");
        AgentRole r = RoleLoader.fromJsonMap(m);
        assertEquals("rich", r.roleId);
        assertEquals("Rich Fields", r.name);
        assertEquals("fuziduan", r.username);
        assertEquals(1500, r.uid);
        assertEquals("T", r.title);
        assertEquals("R", r.responsibilities);
        assertEquals("P", r.personality);
        assertEquals(List.of("s1", "s2"), r.skills);
        assertEquals(Set.of("k1", "k2"), r.interestKeywords);
        assertEquals("E", r.systemPromptExtra);
        assertTrue(r.isDefault);
        assertEquals("G", r.group);
        assertEquals("rich@company.com", r.email);
        assertEquals("local", r.computerKind);
        assertEquals("2", r.computerKwargs.get("cpu"));
        assertEquals(0.6, r.salienceThreshold);
        assertEquals(Types.AgentState.WAIT, r.state);
    }

    /** When the JSON gives no username/uid: username falls back to role_id and uid uses the default (PinyinMap merged into JSON). */
    @Test
    void testFromJsonMapFallsBackUsernameToRoleId() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role_id", "derived");
        m.put("name", "Wang Jianguo");
        m.put("title", "T");
        m.put("responsibilities", "R");
        m.put("personality", "P");
        m.put("skills", List.of("s"));
        m.put("interest_keywords", List.of("k"));
        AgentRole r = RoleLoader.fromJsonMap(m);
        assertEquals("derived", r.username);
        assertEquals(1100, r.uid);
    }

    // ── JSON serialization round trip ─────────────────────────

    /** After a toJsonMap → fromJsonMap round trip, a single template's object equals field by field. */
    @Test
    void testToJsonMapRoundTrip() {
        AgentRole src = RoleLoader.getTemplate("CEO");
        AgentRole back = RoleLoader.fromJsonMap(RoleLoader.toJsonMap(src));
        assertRoleEquals(src, back);
    }

    /** All 55 templates survive a lossless toJsonMap → fromJsonMap round trip. */
    @Test
    void testToJsonMapRoundTripAllTemplates() {
        for (String rid : RoleLoader.TEMPLATES.keySet()) {
            AgentRole src = RoleLoader.getTemplate(rid);
            AgentRole back = RoleLoader.fromJsonMap(RoleLoader.toJsonMap(src));
            assertRoleEquals(src, back);
        }
    }

    /** toJsonMap omits default-valued fields (matching the built-in template file shape). */
    @Test
    void testToJsonMapOmitsDefaults() {
        AgentRole architect = RoleLoader.getTemplate("architect");
        Map<String, Object> m = RoleLoader.toJsonMap(architect);
        assertNull(m.get("is_default"));
        assertNull(m.get("email"));
        assertNull(m.get("state"));
        assertNull(m.get("computer_kind"));
        assertNull(m.get("salience_threshold"));
        assertEquals("Wang Jianguo", m.get("name"));
        assertEquals("architect", m.get("role_id"));
    }

    // ── Registry merge ────────────────────────────────────────

    /** registerFromJson merges external JSON into the global registry. */
    @Test
    void testRegisterFromJsonMerges() throws IOException {
        int before = RoleLoader.TEMPLATES.size();
        String json = """
                {"custom_1": {"name": "Custom", "title": "C", "responsibilities": "x",
                  "personality": "y", "skills": ["s"], "interest_keywords": ["k"]}}
                """;
        try {
            int added = RoleLoader.registerFromJson(json);
            assertEquals(1, added);
            assertEquals(before + 1, RoleLoader.TEMPLATES.size());
            assertTrue(RoleLoader.TEMPLATES.containsKey("custom_1"));
            assertEquals("Custom", RoleLoader.getTemplate("custom_1").name);
        } finally {
            RoleLoader.TEMPLATES.remove("custom_1");  // clean up to avoid affecting other tests
        }
        assertEquals(before, RoleLoader.TEMPLATES.size());
    }

    // ── Helpers ───────────────────────────────────────────────

    private static void assertRoleEquals(AgentRole a, AgentRole b) {
        assertEquals(a.name, b.name);
        assertEquals(a.roleId, b.roleId);
        assertEquals(a.username, b.username);
        assertEquals(a.uid, b.uid);
        assertEquals(a.title, b.title);
        assertEquals(a.responsibilities, b.responsibilities);
        assertEquals(a.personality, b.personality);
        assertEquals(a.skills, b.skills);
        assertEquals(a.interestKeywords, b.interestKeywords);
        assertEquals(a.systemPromptExtra, b.systemPromptExtra);
        assertEquals(a.isDefault, b.isDefault);
        assertEquals(a.group, b.group);
        assertEquals(a.email, b.email);
        assertEquals(a.computerKind, b.computerKind);
        assertEquals(a.computerKwargs, b.computerKwargs);
        assertEquals(a.salienceThreshold, b.salienceThreshold);
        assertEquals(a.state, b.state);
    }
}
