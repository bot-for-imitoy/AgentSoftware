package com.agent.software.agent.dialog;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.sim.clock.ShiftCalendar;
import com.agent.software.sim.clock.SimClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SystemPrompt} 组装测试。
 *
 * <p>迁移自 master {@code AgentRole.buildSystemPrompt()} 的语义：master 把提示词拼装混在
 * 角色对象里，新架构抽成可单测的组装器，只依赖只读 {@code Clock} 与一份
 * {@link DailySummary}。这里验证时间/班次注入、人设/技能/分组邮件规则、以及"昨天总结"
 * 的注入条件（严格早于今天、空白不注入）。
 */
class SystemPromptTest {

    private static SimClock clock() {
        return new SimClock(ShiftCalendar.of(1.0, 8, 18), LocalDate.of(2026, 9, 16));
    }

    private static RoleSpec spec(String... skills) {
        return RoleSpec.builder()
                .id(new RoleId("backend_dev_1"))
                .name("张三")
                .username("zhangsan")
                .title("后端工程师")
                .personality("务实")
                .group("研发组")
                .email("zhangsan@agentsoftware.local")
                .skills(List.of(skills))
                .interestKeywords(Set.of("bug"))
                .toolkits(Set.of())
                .build();
    }

    @Test
    void 注入当前时间与班次几何() {
        String text = new SystemPrompt(clock(), null).build(spec("Java"));

        assertTrue(text.contains("2026-09-16"), text);
        assertTrue(text.contains("day 1"), text);
        assertTrue(text.contains("8:00–18:00"), text);
        assertTrue(text.contains("1 tick = 1.0 simulated second(s)"), text);
    }

    @Test
    void 注入人设技能与分组邮件规则() {
        String text = new SystemPrompt(clock(), null).build(spec("Java", "Spring"));

        assertTrue(text.contains("张三"), text);
        assertTrue(text.contains("后端工程师"), text);
        assertTrue(text.contains("务实"), text);
        assertTrue(text.contains("Java, Spring"), text);
        assertTrue(text.contains("You belong to the 研发组"), text);
        assertTrue(text.contains("zhangsan@agentsoftware.local"), text);
        assertTrue(text.contains("talk tool can only message members of your own group"), text);
    }

    @Test
    void 无分组时不注入组内规则() {
        RoleSpec noGroup = RoleSpec.builder()
                .id(new RoleId("ceo")).name("Lin").toolkits(Set.of()).build();

        String text = new SystemPrompt(clock(), null).build(noGroup);

        assertFalse(text.contains("You belong to the"));
        assertFalse(text.contains("talk tool can only message members of your own group"));
    }

    @Test
    void 缺少显式邮箱时回退到示例域名() {
        RoleSpec noMail = RoleSpec.builder()
                .id(new RoleId("ceo")).name("Lin").username("lin")
                .group("管理层").toolkits(Set.of()).build();

        String text = new SystemPrompt(clock(), null).build(noMail);

        assertTrue(text.contains("lin@company.com"), text);
    }

    @Test
    void 注入昨日总结并传入今天的天数() {
        AtomicInteger askedBeforeDay = new AtomicInteger(-1);
        DailySummary summary = (owner, beforeDay) -> {
            askedBeforeDay.set(beforeDay);
            return Optional.of("昨天完成了登录页。");
        };

        String text = new SystemPrompt(clock(), summary).build(spec("Java"));

        assertEquals(1, askedBeforeDay.get(), "应查询严格早于今天（day 1）的总结");
        assertTrue(text.contains("[Yesterday's Summary]"), text);
        assertTrue(text.contains("昨天完成了登录页。"), text);
    }

    @Test
    void 没有昨日总结时不注入() {
        DailySummary empty = (owner, beforeDay) -> Optional.empty();
        String text = new SystemPrompt(clock(), empty).build(spec("Java"));
        assertFalse(text.contains("[Yesterday's Summary]"));
    }

    @Test
    void 空白总结不注入() {
        DailySummary blank = (owner, beforeDay) -> Optional.of("   ");
        String text = new SystemPrompt(clock(), blank).build(spec("Java"));
        assertFalse(text.contains("[Yesterday's Summary]"));
    }

    @Test
    void 注入角色的额外提示词() {
        RoleSpec extra = RoleSpec.builder()
                .id(new RoleId("ceo")).name("Lin")
                .promptExtra("Always cc the CTO.").toolkits(Set.of()).build();

        String text = new SystemPrompt(clock(), null).build(extra);

        assertTrue(text.contains("Always cc the CTO."), text);
    }

    @Test
    void 暴露只读时钟() {
        SimClock clock = clock();
        assertEquals(clock, new SystemPrompt(clock, null).clock());
    }
}
