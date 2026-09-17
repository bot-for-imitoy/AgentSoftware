package com.agent.software.agent.dispatch;

import com.agent.software.agent.AgentState;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryContext;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryDecision;
import com.agent.software.agent.dispatch.DeliveryPolicy.DeliveryVerdict;
import com.agent.software.agent.dispatch.SaliencePolicy.SalienceDecision;
import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Payload;
import com.agent.software.sim.event.AgentEvent;
import com.agent.software.sim.event.EventKind;
import com.agent.software.sim.event.Priority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投递策略测试。
 *
 * <p>迁移自 master 的 {@code TaskDueStateMaskTest}（定向定时提醒必须穿透状态掩码）与
 * {@code AgentRole.evaluateEvent} 的过滤语义。master 把同一个决定拆在
 * {@code EventDispatcher} 的定向/定时特例、{@code AgentRole} 的状态掩码与
 * {@code RolePool} 的下班保留三处；新架构收拢到 {@link DefaultDeliveryPolicy} 一个点。
 *
 * <p>特别覆盖 master 的"一次性提醒必须送达"：{@code TASK_DUE} 只触发一次，若在
 * WAIT / OFF_DUTY 被丢弃就永远丢了。
 */
class DeliveryPolicyTest {

    /** 可控的显著性策略：记录被问过几次，返回固定结论。 */
    private static final class FakeSalience implements SaliencePolicy {
        private final boolean pass;
        private int calls;

        FakeSalience(boolean pass) {
            this.pass = pass;
        }

        @Override
        public SalienceDecision score(RoleSpec spec, AgentEvent event) {
            calls++;
            return new SalienceDecision(pass, pass ? 0.9 : 0.1, pass ? 0.9 : 0.1,
                    pass ? "假通过" : "假不通过");
        }
    }

    private static RoleSpec spec(Set<String> keywords, List<String> skills, double threshold) {
        return RoleSpec.builder()
                .id(new RoleId("ceo")).name("Lin").username("lin")
                .interestKeywords(keywords).skills(skills)
                .salienceThreshold(threshold)
                .toolkits(Set.of())
                .build();
    }

    private static RoleSpec spec() {
        return spec(Set.of(), List.of(), 0.4);
    }

    // ── EMERGENCY 穿透 ──────────────────────────────────────────

    @Test
    void 紧急事件穿透所有状态与显著性() {
        FakeSalience salience = new FakeSalience(false);
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(salience);
        AgentEvent urgent = AgentEvent.broadcast(new EventKind("email", "NEW_MAIL"),
                Priority.EMERGENCY, Payload.of("title", "server down"));

        for (AgentState state : AgentState.values()) {
            DeliveryDecision decision = policy.decide(new DeliveryContext(urgent, spec(), state, false));
            assertEquals(DeliveryVerdict.DELIVER, decision.verdict(), "EMERGENCY 必须穿透 " + state);
        }
        assertEquals(0, salience.calls, "紧急事件不应走内容显著性打分");
    }

    // ── 定向定时提醒穿透 ────────────────────────────────────────

    @Test
    void 定向定时提醒穿透状态限制() {
        FakeSalience salience = new FakeSalience(false);
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(salience);
        AgentEvent reminder = AgentEvent.toRole(new RoleId("ceo"), EventKind.TASK_DUE,
                Priority.NORMAL, Payload.of("title", "交周报"));

        for (AgentState state : List.of(AgentState.OFF_DUTY, AgentState.WRAPPING_UP, AgentState.WAITING)) {
            DeliveryDecision decision = policy.decide(new DeliveryContext(reminder, spec(), state, true));
            assertEquals(DeliveryVerdict.DELIVER, decision.verdict(),
                    "一次性提醒在 " + state + " 下也必须送达");
        }
        assertEquals(0, salience.calls, "提醒由 scheduledReminder 短路，不参与显著性过滤");
    }

    @Test
    void 广播形式的定时提醒同样穿透并跳过显著性() {
        FakeSalience salience = new FakeSalience(false);
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(salience);
        AgentEvent reminder = AgentEvent.broadcast(EventKind.TASK_DUE, Priority.NORMAL,
                Payload.of("title", "全员提醒"));

        DeliveryDecision decision = policy.decide(new DeliveryContext(reminder, spec(), AgentState.OFF_DUTY, true));
        assertEquals(DeliveryVerdict.DELIVER, decision.verdict());
        assertEquals(0, salience.calls);
    }

    // ── 广播：显著性不过 → DROP ─────────────────────────────────

    @Test
    void 广播显著性不足直接丢弃() {
        FakeSalience salience = new FakeSalience(false);
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(salience);
        AgentEvent broadcast = AgentEvent.broadcast(new EventKind("email", "NEW_MAIL"),
                Priority.NORMAL, Payload.of("title", "团建通知"));

        DeliveryDecision decision = policy.decide(new DeliveryContext(broadcast, spec(), AgentState.ON_DUTY_IDLE, false));

        assertEquals(DeliveryVerdict.DROP, decision.verdict());
        assertTrue(decision.reason().contains("显著性"), decision.reason());
        assertEquals(1, salience.calls);
    }

    // ── 广播：显著性通过 → 看状态 DELIVER / HOLD ────────────────

    @Test
    void 广播显著性通过后按状态决定投递或暂存() {
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(new FakeSalience(true));
        AgentEvent broadcast = AgentEvent.broadcast(new EventKind("email", "NEW_MAIL"),
                Priority.NORMAL, Payload.of("title", "需求评审"));

        for (AgentState state : List.of(AgentState.OFF_DUTY, AgentState.WRAPPING_UP, AgentState.WAITING)) {
            assertEquals(DeliveryVerdict.HOLD,
                    policy.decide(new DeliveryContext(broadcast, spec(), state, false)).verdict(),
                    state + " 应暂存");
        }
        for (AgentState state : List.of(AgentState.ON_DUTY_IDLE, AgentState.ON_DUTY_BUSY)) {
            assertEquals(DeliveryVerdict.DELIVER,
                    policy.decide(new DeliveryContext(broadcast, spec(), state, false)).verdict(),
                    state + " 应立即投递");
        }
    }

    // ── 时间来源的系统事件不做内容过滤 ──────────────────────────

    @Test
    void 时间来源的广播事件跳过显著性过滤() {
        FakeSalience salience = new FakeSalience(false);
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(salience);
        AgentEvent shiftEnd = AgentEvent.broadcast(EventKind.SHIFT_END, Priority.NORMAL, Payload.empty());

        DeliveryDecision decision = policy.decide(new DeliveryContext(shiftEnd, spec(), AgentState.ON_DUTY_IDLE, false));

        assertEquals(DeliveryVerdict.DELIVER, decision.verdict());
        assertEquals(0, salience.calls, "source=time 的系统事件不做内容过滤");
    }

    // ── 定向普通事件：与 master 的刻意差异 ──────────────────────

    @Test
    void 定向但非提醒的普通事件在下班时暂存而不是丢弃() {
        // master 对"定向但非紧急"的事件在角色下班时直接丢弃；新策略改成 HOLD ——
        // 进暂存队列，第二天上班时提升。丢事件没有正当理由，暂存才有。
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(new FakeSalience(true));
        AgentEvent mail = AgentEvent.toRole(new RoleId("ceo"), EventKind.NEW_MAIL,
                Priority.NORMAL, Payload.of("title", "新邮件"));

        assertEquals(DeliveryVerdict.HOLD,
                policy.decide(new DeliveryContext(mail, spec(), AgentState.OFF_DUTY, false)).verdict());
        assertEquals(DeliveryVerdict.DELIVER,
                policy.decide(new DeliveryContext(mail, spec(), AgentState.ON_DUTY_IDLE, false)).verdict());
    }

    // ── 边界 ────────────────────────────────────────────────────

    @Test
    void 未配置显著性策略时广播默认通过() {
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(null);
        AgentEvent broadcast = AgentEvent.broadcast(new EventKind("email", "NEW_MAIL"),
                Priority.NORMAL, Payload.empty());

        assertEquals(DeliveryVerdict.DELIVER,
                policy.decide(new DeliveryContext(broadcast, spec(), AgentState.ON_DUTY_IDLE, false)).verdict());
    }

    @Test
    void 空事件直接丢弃() {
        DefaultDeliveryPolicy policy = new DefaultDeliveryPolicy(new FakeSalience(true));
        assertEquals(DeliveryVerdict.DROP, policy.decide(null).verdict());
        assertEquals(DeliveryVerdict.DROP,
                policy.decide(new DeliveryContext(null, spec(), AgentState.ON_DUTY_IDLE, false)).verdict());
    }

    // ── KeywordSaliencePolicy：确定性纯函数断言 ─────────────────

    @Test
    void 关键词显著性打分是确定性的纯函数() {
        KeywordSaliencePolicy policy = KeywordSaliencePolicy.defaults();
        RoleSpec spec = spec(Set.of("bug"), List.of("Java"), 0.4);
        AgentEvent event = AgentEvent.broadcast(new EventKind("email", "NEW_MAIL"),
                Priority.NORMAL, Payload.of("title", "urgent bug in login page"));

        SalienceDecision decision = policy.score(spec, event);

        // relevance = 0.25(基础) + 0.25(命中 bug) + 0.15(urgent) = 0.65
        assertEquals(0.65, decision.relevance(), 1e-9);
        // score = 3/10*0.4 + 0.65*0.6 = 0.12 + 0.39
        assertEquals(0.51, decision.score(), 1e-9);
        assertTrue(decision.pass());
    }

    @Test
    void 关键词加分有上限() {
        KeywordSaliencePolicy policy = KeywordSaliencePolicy.defaults();
        RoleSpec spec = spec(Set.of("bug", "login", "page", "urgent", "critical"), List.of(), 0.4);
        AgentEvent event = AgentEvent.broadcast(new EventKind("email", "NEW_MAIL"),
                Priority.NORMAL, Payload.of("title", "bug login page urgent critical"));

        SalienceDecision decision = policy.score(spec, event);

        // 5 次命中但关键词加分封顶 0.60 → 0.25 + 0.60 + 0.15 = 1.00
        assertEquals(1.0, decision.relevance(), 1e-9);
    }

    @Test
    void 技能命中加分() {
        KeywordSaliencePolicy policy = KeywordSaliencePolicy.defaults();
        RoleSpec spec = spec(Set.of(), List.of("Java"), 0.4);
        AgentEvent event = AgentEvent.broadcast(new EventKind("email", "NEW_MAIL"),
                Priority.NORMAL, Payload.of("title", "please check java service"));

        SalienceDecision decision = policy.score(spec, event);

        assertEquals(0.35, decision.relevance(), 1e-9, "基础 0.25 + 技能命中 0.10");
    }

    @Test
    void 不命中时使用基础相关性且低分事件被拒绝() {
        KeywordSaliencePolicy policy = KeywordSaliencePolicy.defaults();
        RoleSpec spec = spec(Set.of("bug"), List.of("Java"), 0.9);
        AgentEvent event = AgentEvent.broadcast(new EventKind("email", "NEW_MAIL"),
                Priority.LOW, Payload.of("title", "team lunch"));

        SalienceDecision decision = policy.score(spec, event);

        assertEquals(0.25, decision.relevance(), 1e-9);
        // score = 1/10*0.4 + 0.25*0.6 = 0.19 < 0.9
        assertEquals(0.19, decision.score(), 1e-9);
        assertFalse(decision.pass());
        assertTrue(decision.reason().contains("未通过"));
    }
}
