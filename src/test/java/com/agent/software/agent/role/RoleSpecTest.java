package com.agent.software.agent.role;

import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoleSpec} 的 16 字段构造器 / 缺省值 / 校验 / {@link RoleSpec.ComputerSpec} 容错。
 *
 * <p>对应 master {@code AgentRole.builder()} 的静态字段装配：master 是可变对象 + 公开字段，
 * 这里是不可变 record + 紧凑构造器（缺省值全部内聚在构造器里）。
 */
class RoleSpecTest {

    @Test
    void builder装配全部16个字段() {
        RoleSpec.ComputerSpec computer = new RoleSpec.ComputerSpec("podman", Map.of("cpu", "2"));
        RoleSpec spec = RoleSpec.builder()
                .id(new RoleId("dev_1"))
                .name("张三")
                .username("zhangsan")
                .uid(1200)
                .title("工程师")
                .responsibilities("写代码")
                .personality("务实")
                .skills(List.of("Java", "SQL"))
                .group("研发组")
                .email("zhangsan@company.com")
                .promptExtra("保持简洁")
                .interestKeywords(new LinkedHashSet<>(List.of("bug", "feature")))
                .salienceThreshold(0.7)
                .computer(computer)
                .toolkits(new LinkedHashSet<>(List.of("note", "talk")))
                .defaultRole(true)
                .build();

        assertEquals(new RoleId("dev_1"), spec.id());
        assertEquals("张三", spec.name());
        assertEquals("zhangsan", spec.username());
        assertEquals(1200, spec.uid());
        assertEquals("工程师", spec.title());
        assertEquals("写代码", spec.responsibilities());
        assertEquals("务实", spec.personality());
        assertEquals(List.of("Java", "SQL"), spec.skills());
        assertEquals("研发组", spec.group());
        assertEquals("zhangsan@company.com", spec.email());
        assertEquals("保持简洁", spec.promptExtra());
        assertEquals(Set.of("bug", "feature"), spec.interestKeywords());
        assertEquals(0.7, spec.salienceThreshold());
        assertSame(computer, spec.computer());
        assertEquals(Set.of("note", "talk"), spec.toolkits());
        assertTrue(spec.defaultRole());
        assertTrue(spec.hasGroup());
    }

    @Test
    void 缺少id抛DomainError() {
        DomainError fromBuilder = assertThrows(DomainError.class, () -> RoleSpec.builder().name("无 id").build());
        assertEquals("role.id.null", fromBuilder.code());

        DomainError fromCanonical = assertThrows(DomainError.class, () -> new RoleSpec(
                null, "无 id", "u", 1, "t", "r", "p", List.of(), "g", "e", "pe",
                Set.of(), 0.4, null, Set.of(), false));
        assertEquals("role.id.null", fromCanonical.code());
    }

    @Test
    void 空集合与空字符串归一为缺省值() {
        RoleSpec spec = RoleSpec.builder().id(new RoleId("solo")).build();
        assertEquals("", spec.name());
        // username 缺失/空白时退回 role_id（新架构不再做姓名→拼音推导）
        assertEquals("solo", spec.username());
        assertEquals(0, spec.uid());
        assertEquals("", spec.title());
        assertEquals("", spec.responsibilities());
        assertEquals("", spec.personality());
        assertEquals(List.of(), spec.skills());
        assertEquals("", spec.group());
        assertEquals("", spec.email());
        assertEquals("", spec.promptExtra());
        assertEquals(Set.of(), spec.interestKeywords());
        assertEquals(RoleSpec.DEFAULT_SALIENCE_THRESHOLD, spec.salienceThreshold());
        assertEquals("local", spec.computer().kind());
        assertEquals(RoleSpec.DEFAULT_TOOLKITS, spec.toolkits());
        assertFalse(spec.defaultRole());
        assertFalse(spec.hasGroup());
    }

    @Test
    void 非法显著性阈值回退默认值() {
        // master 的 salienceThreshold 默认 0.4；非正数一律视为"没配置"
        assertEquals(RoleSpec.DEFAULT_SALIENCE_THRESHOLD,
                RoleSpec.builder().id(new RoleId("a")).salienceThreshold(0).build().salienceThreshold());
        assertEquals(RoleSpec.DEFAULT_SALIENCE_THRESHOLD,
                RoleSpec.builder().id(new RoleId("b")).salienceThreshold(-1.5).build().salienceThreshold());
    }

    @Test
    void hasGroup区分空白与缺失() {
        assertFalse(RoleSpec.builder().id(new RoleId("a")).build().hasGroup());
        assertFalse(RoleSpec.builder().id(new RoleId("b")).group("   ").build().hasGroup());
        assertTrue(RoleSpec.builder().id(new RoleId("c")).group("研发组").build().hasGroup());
    }

    @Test
    void 默认工具包集合与master对齐() {
        assertEquals(Set.of("memory", "note", "time", "todo", "task_view", "pc", "mcp_manager", "skill", "email"),
                RoleSpec.DEFAULT_TOOLKITS);
        // builder 不给 toolkits 时应带上全部默认工具包
        assertEquals(RoleSpec.DEFAULT_TOOLKITS,
                RoleSpec.builder().id(new RoleId("d")).build().toolkits());
    }

    @Test
    void ComputerSpec对空值与空白kind容错() {
        RoleSpec.ComputerSpec nullKind = new RoleSpec.ComputerSpec(null, null);
        assertEquals("local", nullKind.kind());
        assertTrue(nullKind.options().isEmpty());

        RoleSpec.ComputerSpec blankKind = new RoleSpec.ComputerSpec("   ", Map.of());
        assertEquals("local", blankKind.kind());

        RoleSpec.ComputerSpec normalized = new RoleSpec.ComputerSpec("  PODMAN ", Map.of("cpu", "2"));
        assertEquals("podman", normalized.kind());
        assertEquals("2", normalized.options().get("cpu"));
    }

    @Test
    void ComputerSpec防御式拷贝options() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("cpu", "2");
        RoleSpec.ComputerSpec spec = new RoleSpec.ComputerSpec("local", source);
        source.put("mem", "8");
        assertFalse(spec.options().containsKey("mem"), "外部修改不应影响已构造的 options");
        assertThrows(UnsupportedOperationException.class, () -> spec.options().put("disk", "1"));

        assertEquals("2", spec.option("cpu", "0"));
        assertEquals("0", spec.option("mem", "0"));
        assertEquals("fallback", new RoleSpec.ComputerSpec("local", java.util.Collections.singletonMap("blank", "  "))
                .option("blank", "fallback"), "空白选项值应回退 fallback");
    }

    @Test
    void skills与interestKeywords防御式拷贝() {
        List<String> skills = new ArrayList<>(List.of("Java"));
        Set<String> keywords = new LinkedHashSet<>(List.of("bug"));
        RoleSpec spec = RoleSpec.builder().id(new RoleId("e")).skills(skills).interestKeywords(keywords).build();
        skills.add("Python");
        keywords.add("feature");
        assertEquals(List.of("Java"), spec.skills());
        assertEquals(Set.of("bug"), spec.interestKeywords());
        assertThrows(UnsupportedOperationException.class, () -> spec.skills().add("Go"));
    }

    @Test
    void toBuilder按原样派生并可覆盖单字段() {
        RoleSpec base = RoleSpec.builder()
                .id(new RoleId("dev_1"))
                .name("张三")
                .username("zhangsan")
                .uid(1200)
                .title("工程师")
                .responsibilities("写代码")
                .personality("务实")
                .skills(List.of("Java"))
                .group("研发组")
                .email("zhangsan@company.com")
                .promptExtra("保持简洁")
                .interestKeywords(Set.of("bug"))
                .salienceThreshold(0.7)
                .computer(new RoleSpec.ComputerSpec("ssh", Map.of("host", "box")))
                .toolkits(Set.of("note"))
                .defaultRole(true)
                .build();

        RoleSpec derived = base.toBuilder().id(new RoleId("dev_2")).name("李四").build();
        assertEquals(new RoleId("dev_2"), derived.id());
        assertEquals("李四", derived.name());
        // 未覆盖的字段原样保留
        assertEquals(base.username(), derived.username());
        assertEquals(base.uid(), derived.uid());
        assertEquals(base.title(), derived.title());
        assertEquals(base.responsibilities(), derived.responsibilities());
        assertEquals(base.personality(), derived.personality());
        assertEquals(base.skills(), derived.skills());
        assertEquals(base.group(), derived.group());
        assertEquals(base.email(), derived.email());
        assertEquals(base.promptExtra(), derived.promptExtra());
        assertEquals(base.interestKeywords(), derived.interestKeywords());
        assertEquals(base.salienceThreshold(), derived.salienceThreshold());
        assertEquals(base.computer(), derived.computer());
        assertEquals(base.toolkits(), derived.toolkits());
        assertEquals(base.defaultRole(), derived.defaultRole());
        assertNotEquals(base.id(), derived.id());
    }

    @Test
    void addToolkit在默认集合之上追加() {
        RoleSpec spec = RoleSpec.builder().id(new RoleId("lead")).addToolkit("client").build();
        assertTrue(spec.toolkits().containsAll(RoleSpec.DEFAULT_TOOLKITS));
        assertTrue(spec.toolkits().contains("client"));
    }
}
