package com.agent.software.bootstrap;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.company.Company;
import com.agent.software.infra.config.AppConfig;
import com.agent.software.infra.json.JacksonJsonCodec;
import com.agent.software.infra.json.JsonCodec;
import com.agent.software.kernel.Ids.RoleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code role_templates.json} → {@link RoleSpec} 的加载测试。
 *
 * <p>master 对应 {@code role.RoleLoaderJsonTest}。新架构把 RoleLoader 收成
 * {@code bootstrap.RoleTemplates}（包级可见），只保留"读内置模板"这一件事：
 * master 的三种 JSON 形状（list / map / 单对象）与 toJsonMap 序列化往返被有意移除，
 * 因为模板文件与快照都只有"扁平对象"这一种形状。
 */
class RoleTemplatesTest {

    private static final JsonCodec JSON = new JacksonJsonCodec();

    @TempDir
    Path tmp;

    // ── 内置资源 ───────────────────────────────────────────────

    @Test
    void 资源加载全部55个模板且默认角色齐全() {
        List<RoleSpec> specs = RoleTemplates.load(JSON);
        assertEquals(55, specs.size(), "内置模板数量应为 55（role_templates.json）");

        Set<String> ids = specs.stream().map(s -> s.id().value()).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String rid : List.of("CEO", "COO", "HR", "CFO")) {
            assertTrue(ids.contains(rid), "缺少默认角色：" + rid);
        }
        // 顺序稳定：按 role_id 字典序（TreeMap）——默认团队装配因此可复现
        List<String> order = specs.stream().map(s -> s.id().value()).toList();
        List<String> sorted = new ArrayList<>(ids);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, order);
    }

    @Test
    void 资源JSON文件字段完整() throws IOException {
        String resource = new String(
                resourceStream(RoleTemplates.RESOURCE).readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> root = JSON.readMap(resource);
        assertEquals(55, root.size());
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            String id = entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> conf = (Map<String, Object>) entry.getValue();
            assertNotNull(conf.get("name"), id + " 缺少 name");
            // username 直接写在模板里（不再从姓名推导拼音），格式为小写 ASCII
            String username = string(conf, "username");
            assertTrue(username.matches("[a-z0-9_]+"), id + " 的 username 非法或缺失：" + username);
            assertNotNull(conf.get("title"), id + " 缺少 title");
            assertNotNull(conf.get("responsibilities"), id + " 缺少 responsibilities");
            assertNotNull(conf.get("personality"), id + " 缺少 personality");
            assertTrue(stringList(conf.get("skills")).size() > 0, id + " 的 skills 为空");
            assertTrue(stringList(conf.get("interest_keywords")).size() > 0, id + " 的 interest_keywords 为空");
            assertEquals(id, string(conf, "role_id"), id + " 的 role_id 与 key 不一致");
        }
    }

    // ── 字段映射与缺省值 ────────────────────────────────────────

    @Test
    void 模板字段映射到RoleSpec() {
        Map<String, RoleSpec> byId = byId(RoleTemplates.load(JSON));

        RoleSpec architect = byId.get("architect");
        assertEquals("Wang Jianguo", architect.name());
        assertEquals("wangjianguo", architect.username());
        assertEquals("System Architect", architect.title());
        assertEquals("Architecture & Release Group", architect.group());
        assertFalse(architect.defaultRole());
        assertTrue(architect.skills().contains("C4 Model"));
        assertTrue(architect.interestKeywords().contains("architecture"));
        assertFalse(architect.promptExtra().isBlank());

        RoleSpec ceo = byId.get("CEO");
        assertEquals("Lin Zong", ceo.name());
        assertEquals("linzong", ceo.username());
        assertEquals("Leadership Group", ceo.group());
        assertTrue(ceo.defaultRole());

        RoleSpec rm = byId.get("release_manager");
        assertTrue(rm.promptExtra().contains("/mnt/drive/Public/work/"));
        assertTrue(rm.promptExtra().contains("git init"));

        RoleSpec lead = byId.get("frontend_lead");
        assertTrue(lead.promptExtra().contains("Fang Jinyan"));
        assertTrue(lead.promptExtra().contains("review"));

        RoleSpec tester = byId.get("tester_20");
        assertEquals("Ruan Zhiming", tester.name());
        assertEquals("Testing Group", tester.group());
    }

    @Test
    void 只有管理角色标记为默认() {
        Set<String> defaultIds = Set.of("CEO", "COO", "HR", "CFO");
        for (RoleSpec spec : RoleTemplates.load(JSON)) {
            assertEquals(defaultIds.contains(spec.id().value()), spec.defaultRole(),
                    spec.id().value() + " 的 is_default 不正确");
        }
    }

    @Test
    void 缺省值全部落在RoleSpec构造器里() {
        for (RoleSpec spec : RoleTemplates.load(JSON)) {
            assertEquals(RoleSpec.DEFAULT_SALIENCE_THRESHOLD, spec.salienceThreshold(),
                    spec.id().value() + " 的显著性阈值应为默认值");
            assertEquals("local", spec.computer().kind(), spec.id().value() + " 的电脑形态应为 local");
            assertTrue(spec.computer().options().isEmpty(), spec.id().value() + " 不应带电脑选项");
            assertEquals("", spec.email(), spec.id().value() + " 不应带邮箱");
            assertFalse(spec.skills().isEmpty());
            assertFalse(spec.interestKeywords().isEmpty());
        }
    }

    @Test
    void 领导组自动获得client工具包() {
        for (RoleSpec spec : RoleTemplates.load(JSON)) {
            assertTrue(spec.toolkits().containsAll(RoleSpec.DEFAULT_TOOLKITS),
                    spec.id().value() + " 缺少默认工具包");
            if (RoleTemplates.LEADERSHIP_GROUP.equals(spec.group())) {
                assertTrue(spec.toolkits().contains("client"),
                        "领导组成员应自动获得 client 工具包：" + spec.id().value());
            } else {
                assertFalse(spec.toolkits().contains("client"),
                        "非领导组不应有 client 工具包：" + spec.id().value());
            }
        }
    }

    @Test
    void uid按排序序号分配且互不相同() {
        List<RoleSpec> specs = RoleTemplates.load(JSON);
        Set<Integer> uids = new LinkedHashSet<>();
        for (RoleSpec spec : specs) {
            assertTrue(spec.uid() >= 1101 && spec.uid() <= 1155, "uid 超出预期区间：" + spec.uid());
            assertTrue(uids.add(spec.uid()), "uid 重复：" + spec.uid());
        }
        // 排序后的第一个模板（CEO）拿 1100 + 1 = 1101
        assertEquals("CEO", specs.get(0).id().value());
        assertEquals(1101, specs.get(0).uid());
        assertEquals(1155, specs.get(54).uid());
    }

    @Test
    void interestKeywords与skills是列表() {
        Map<String, RoleSpec> byId = byId(RoleTemplates.load(JSON));
        RoleSpec backend = byId.get("backend_dev_1");
        assertTrue(backend.skills().size() > 1, "skills 应按列表逐项解析");
        assertTrue(backend.interestKeywords().size() > 1, "interest_keywords 应按列表逐项解析");
        // 集合语义：去重
        assertEquals(backend.skills().size(), Set.copyOf(backend.skills()).size());
    }

    // ── 过滤与 bootstrap 复用 ──────────────────────────────────

    @Test
    void withIds按roleId过滤() {
        List<RoleSpec> all = RoleTemplates.load(JSON);
        List<RoleSpec> picked = RoleTemplates.withIds(all, Set.of("CEO", "CTO"));
        assertEquals(2, picked.size());
        assertEquals(Set.of(new RoleId("CEO"), new RoleId("CTO")),
                picked.stream().map(RoleSpec::id).collect(Collectors.toSet()));
        // 空集合 = 全选（契约：ids 为空表示不过滤）
        assertEquals(55, RoleTemplates.withIds(all, Set.of()).size());
        assertEquals(55, RoleTemplates.withIds(all, null).size());
    }

    @Test
    void defaultTeam返回内置模板() {
        AppConfig config = config(tmp);
        List<RoleSpec> team = Main.defaultTeam(config);
        assertEquals(55, team.size());
        assertTrue(team.stream().anyMatch(s -> "CEO".equals(s.id().value())));
    }

    @Test
    void buildCompany装配默认团队且可安全停止() {
        AppConfig config = config(tmp);
        Company company = Main.buildCompany(config);
        try {
            assertEquals(55, company.team().agents().size());
            assertEquals(55, company.roster().size());
            assertTrue(company.roster().stream().anyMatch(s -> "CEO".equals(s.id().value())));
            assertFalse(company.paused());
        } finally {
            company.stop();
        }
    }

    // ── 助手 ───────────────────────────────────────────────────

    private static AppConfig config(Path dir) {
        AppConfig defaults = AppConfig.defaults();
        return new AppConfig(
                new AppConfig.Llm("openai", "test-model", "k", "http://localhost:1",
                        new AppConfig.Llm.Retry(1, 0.01, 5)),
                defaults.schedule(),
                new AppConfig.Storage(dir.toString()),
                defaults.web(),
                defaults.mail(),
                defaults.toolkits());
    }

    private static Map<String, RoleSpec> byId(List<RoleSpec> specs) {
        Map<String, RoleSpec> out = new TreeMap<>();
        for (RoleSpec spec : specs) {
            out.put(spec.id().value(), spec);
        }
        return out;
    }

    private static InputStream resourceStream(String name) {
        InputStream in = RoleTemplatesTest.class.getClassLoader().getResourceAsStream(name);
        assertNotNull(in, "找不到资源：" + name);
        return in;
    }

    private static String string(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item));
                }
            }
        }
        return out;
    }
}
