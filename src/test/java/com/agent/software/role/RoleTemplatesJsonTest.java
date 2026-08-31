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
 * RoleTemplates JSON 化测试: 内置 role_templates.json 资源、JSON → AgentRole 加载器.
 */
class RoleTemplatesJsonTest {

    @TempDir
    Path tmp;

    // ── 内置资源 ────────────────────────────────────────────

    /** 内置 JSON 资源必须完整还原出 54 个角色模板, 且默认角色齐全. */
    @Test
    void testResourceLoadsAllTemplates() {
        assertEquals(54, RoleTemplates.TEMPLATES.size(),
                "内置模板数量应为 54 (role_templates.json)");
        for (String rid : RoleTemplates.DEFAULT_ROLES) {
            assertTrue(RoleTemplates.TEMPLATES.containsKey(rid), "默认角色缺失: " + rid);
        }
    }

    /** 资源 JSON 文件本身完整: 必填字段非空, role_id 与键一致, skills/keywords 非空. */
    @SuppressWarnings("unchecked")
    @Test
    void testResourceJsonComplete() throws IOException {
        String resource = new String(
                RoleTemplates.class.getClassLoader().getResourceAsStream(
                        RoleTemplates.DEFAULT_TEMPLATES_RESOURCE).readAllBytes(),
                StandardCharsets.UTF_8);
        Map<String, Object> root = Json.parseObject(resource);
        assertEquals(54, root.size());
        for (Map.Entry<String, Object> e : root.entrySet()) {
            String id = e.getKey();
            Map<String, Object> conf = (Map<String, Object>) e.getValue();
            assertNotNull(conf.get("name"), id + " 缺 name");
            // username: 每个模板都显式给出拼音用户名 (替代原 PinyinMap), 合法格式小写 ASCII
            String username = Json.str(conf, "username", "");
            assertTrue(username.matches("[a-z0-9_]+"), id + " 的 username '" + username + "' 非法或缺失");
            assertNotNull(conf.get("title"), id + " 缺 title");
            assertNotNull(conf.get("responsibilities"), id + " 缺 responsibilities");
            assertNotNull(conf.get("personality"), id + " 缺 personality");
            assertTrue(Json.strList(conf, "skills").size() > 0, id + " skills 为空");
            assertTrue(Json.strList(conf, "interest_keywords").size() > 0,
                    id + " interest_keywords 为空");
            assertEquals(id, Json.str(conf, "role_id", ""), id + " role_id 与键不一致");
        }
    }

    // ── 注册表抽查 ──────────────────────────────────────────

    @Test
    void testTemplateSpotChecks() {
        AgentRole architect = RoleTemplates.getTemplate("architect");
        assertEquals("王建国", architect.name);
        assertEquals("System Architect", architect.title);
        assertEquals("架构与版本组", architect.group);
        assertFalse(architect.isDefault);
        assertTrue(architect.skills.contains("C4 Model"));
        assertTrue(architect.interestKeywords.contains("架构"));

        AgentRole ceo = RoleTemplates.getTemplate("CEO");
        assertEquals("林总", ceo.name);
        assertTrue(ceo.isDefault);
        assertEquals("领导组", ceo.group);

        AgentRole rm = RoleTemplates.getTemplate("release_manager");
        assertTrue(rm.systemPromptExtra.contains("/mnt/drive/Public/work/"));
        assertTrue(rm.systemPromptExtra.contains("git init"));

        AgentRole lead = RoleTemplates.getTemplate("frontend_lead");
        assertTrue(lead.systemPromptExtra.contains("方谨言"));
        assertTrue(lead.systemPromptExtra.contains("审核"));

        AgentRole tester = RoleTemplates.getTemplate("tester_20");
        assertEquals("阮志明", tester.name);
        assertEquals("测试组", tester.group);
    }

    /** 只有管理角色 (CEO/COO/HR/CFO) 标记为 is_default. */
    @Test
    void testOnlyManagementRolesAreDefault() {
        Set<String> defaultIds = Set.of("CEO", "COO", "HR", "CFO");
        for (Map.Entry<String, Supplier<AgentRole>> e : RoleTemplates.TEMPLATES.entrySet()) {
            assertEquals(defaultIds.contains(e.getKey()),
                    e.getValue().get().isDefault, e.getKey() + " is_default 不正确");
        }
    }

    // ── JSON → AgentRole 加载器 ─────────────────────────────

    /** 形态②: JSON 数组 → 角色对象列表, 每次加载独立副本. */
    @Test
    void testLoadFromJsonListForm() throws IOException {
        String json = """
                [
                  {
                    "role_id": "alpha",
                    "name": "张三",
                    "title": "测试角色",
                    "responsibilities": "负责测试",
                    "personality": "认真负责",
                    "skills": ["测试", "质量"],
                    "interest_keywords": ["测试", "quality"],
                    "system_prompt_extra": "输出简洁",
                    "group": "测试组"
                  },
                  {
                    "role_id": "beta",
                    "name": "李四",
                    "title": "开发角色",
                    "responsibilities": "负责开发",
                    "personality": "务实",
                    "skills": ["Java"],
                    "interest_keywords": ["开发"]
                  }
                ]
                """;
        List<AgentRole> roles = RoleTemplates.loadFromJson(json);
        assertEquals(2, roles.size());
        AgentRole a = roles.get(0);
        assertEquals("alpha", a.roleId);
        assertEquals("张三", a.name);
        assertEquals("测试组", a.group);
        assertTrue(a.skills.contains("测试"));
        assertTrue(a.interestKeywords.contains("quality"));
        assertEquals("输出简洁", a.systemPromptExtra);
        AgentRole b = roles.get(1);
        assertEquals("beta", b.roleId);
        assertFalse(b.isDefault);
        assertEquals("", b.systemPromptExtra);  // 未给 extra → 默认空串
        // 独立副本: 修改一个不影响另一个
        roles.get(0).skills.add("新增");
        assertFalse(roles.get(1).skills.contains("新增"));
    }

    /** 形态①: role_id → 配置 映射 → 工厂表, 且工厂每次返回独立副本. */
    @Test
    void testTemplatesFromJsonMapForm() throws IOException {
        String json = """
                {
                  "alpha": {
                    "name": "张三",
                    "title": "测试角色",
                    "responsibilities": "负责测试",
                    "personality": "认真负责",
                    "skills": ["测试"],
                    "interest_keywords": ["测试"]
                  }
                }
                """;
        Map<String, Supplier<AgentRole>> table = RoleTemplates.templatesFromJson(json);
        assertEquals(Set.of("alpha"), table.keySet());
        assertEquals("alpha", table.get("alpha").get().roleId);
        assertEquals("张三", table.get("alpha").get().name);
        assertNotSame(table.get("alpha").get(), table.get("alpha").get());
    }

    /** 形态③: 单个角色对象. */
    @Test
    void testLoadFromJsonSingleObjectForm() throws IOException {
        String json = """
                {
                  "role_id": "solo",
                  "name": "独立角色",
                  "title": "Solo",
                  "responsibilities": "x",
                  "personality": "y",
                  "skills": ["a"],
                  "interest_keywords": ["b"]
                }
                """;
        List<AgentRole> roles = RoleTemplates.loadFromJson(json);
        assertEquals(1, roles.size());
        assertEquals("solo", roles.get(0).roleId);
        assertEquals("独立角色", roles.get(0).name);
    }

    /** 从 JSON 文件加载. */
    @Test
    void testLoadFromJsonFile() throws IOException {
        Path f = tmp.resolve("roles.json");
        Files.writeString(f, """
                {
                  "file_role": {
                    "name": "文件角色",
                    "title": "From File",
                    "responsibilities": "x",
                    "personality": "y",
                    "skills": ["s"],
                    "interest_keywords": ["k"]
                  }
                }
                """, StandardCharsets.UTF_8);
        List<AgentRole> roles = RoleTemplates.loadFromJson(f);
        assertEquals(1, roles.size());
        assertEquals("file_role", roles.get(0).roleId);
    }

    /** fromJsonMap 覆盖 AgentRole 全部可配置字段 (含 username/uid/state/阈值/电脑). */
    @Test
    void testFromJsonMapAllFields() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role_id", "rich");
        m.put("name", "富字段");
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
        AgentRole r = RoleTemplates.fromJsonMap(m);
        assertEquals("rich", r.roleId);
        assertEquals("富字段", r.name);
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

    /** JSON 未给 username/uid 时: username 回退 role_id, uid 用默认值 (PinyinMap 已并入 JSON). */
    @Test
    void testFromJsonMapFallsBackUsernameToRoleId() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role_id", "derived");
        m.put("name", "王建国");
        m.put("title", "T");
        m.put("responsibilities", "R");
        m.put("personality", "P");
        m.put("skills", List.of("s"));
        m.put("interest_keywords", List.of("k"));
        AgentRole r = RoleTemplates.fromJsonMap(m);
        assertEquals("derived", r.username);
        assertEquals(1100, r.uid);
    }

    // ── JSON 序列化往返 ──────────────────────────────────────

    /** toJsonMap → fromJsonMap 对单个模板往返后对象逐字段相等. */
    @Test
    void testToJsonMapRoundTrip() {
        AgentRole src = RoleTemplates.getTemplate("CEO");
        AgentRole back = RoleTemplates.fromJsonMap(RoleTemplates.toJsonMap(src));
        assertRoleEquals(src, back);
    }

    /** 全部 54 个模板都能 toJsonMap → fromJsonMap 无损往返. */
    @Test
    void testToJsonMapRoundTripAllTemplates() {
        for (String rid : RoleTemplates.TEMPLATES.keySet()) {
            AgentRole src = RoleTemplates.getTemplate(rid);
            AgentRole back = RoleTemplates.fromJsonMap(RoleTemplates.toJsonMap(src));
            assertRoleEquals(src, back);
        }
    }

    /** toJsonMap 省略默认值字段 (与内置模板文件形态一致). */
    @Test
    void testToJsonMapOmitsDefaults() {
        AgentRole architect = RoleTemplates.getTemplate("architect");
        Map<String, Object> m = RoleTemplates.toJsonMap(architect);
        assertNull(m.get("is_default"));
        assertNull(m.get("email"));
        assertNull(m.get("state"));
        assertNull(m.get("computer_kind"));
        assertNull(m.get("salience_threshold"));
        assertEquals("王建国", m.get("name"));
        assertEquals("architect", m.get("role_id"));
    }

    // ── 注册表合并 ──────────────────────────────────────────

    /** registerFromJson 把外部 JSON 合并进全局注册表. */
    @Test
    void testRegisterFromJsonMerges() throws IOException {
        int before = RoleTemplates.TEMPLATES.size();
        String json = """
                {"custom_1": {"name": "自定义", "title": "C", "responsibilities": "x",
                  "personality": "y", "skills": ["s"], "interest_keywords": ["k"]}}
                """;
        try {
            int added = RoleTemplates.registerFromJson(json);
            assertEquals(1, added);
            assertEquals(before + 1, RoleTemplates.TEMPLATES.size());
            assertTrue(RoleTemplates.TEMPLATES.containsKey("custom_1"));
            assertEquals("自定义", RoleTemplates.getTemplate("custom_1").name);
        } finally {
            RoleTemplates.TEMPLATES.remove("custom_1");  // 清理, 避免影响其他测试
        }
        assertEquals(before, RoleTemplates.TEMPLATES.size());
    }

    // ── 辅助 ────────────────────────────────────────────────

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
