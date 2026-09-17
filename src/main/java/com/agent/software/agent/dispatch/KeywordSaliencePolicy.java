package com.agent.software.agent.dispatch;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.agent.dispatch.SaliencePolicy.SalienceDecision;
import com.agent.software.kernel.Text;
import com.agent.software.sim.event.AgentEvent;

import java.util.Locale;

/**
 * 关键词显著性策略（master {@code AgentRole.evaluateEvent} Layer 2 的等价物）。
 *
 * <p>打分 = 基础分 + 关键词命中 + 技能命中奖励 + 紧急词奖励，再与
 * {@link RoleSpec#salienceThreshold()} 比较。权重全部来自构造参数，便于调参。
 *
 * <p>对齐 master 的默认权重：基础 0.25、每次关键词命中 +0.25（上限 +0.60）、
 * 技能命中 +0.10、紧急词 +0.15；最终分 = 紧急度权重占 0.4 + 相关性占 0.6。
 */
public final class KeywordSaliencePolicy implements SaliencePolicy {

    /** 关键词命中加分的上限（master 的 0.60）。 */
    private static final double KEYWORD_BONUS_CAP = 0.60;

    /** 紧急度在总分里的占比。 */
    private static final double URGENCY_WEIGHT = 0.4;

    /** 相关性在总分里的占比。 */
    private static final double RELEVANCE_WEIGHT = 0.6;

    private final double baseRelevance;
    private final double keywordStep;
    private final double skillBonus;
    private final double urgencyBonus;

    public KeywordSaliencePolicy(double baseRelevance, double keywordStep,
                                 double skillBonus, double urgencyBonus) {
        this.baseRelevance = baseRelevance;
        this.keywordStep = keywordStep;
        this.skillBonus = skillBonus;
        this.urgencyBonus = urgencyBonus;
    }

    /** master 的默认权重组合。 */
    public static KeywordSaliencePolicy defaults() {
        return new KeywordSaliencePolicy(0.25, 0.25, 0.10, 0.15);
    }

    @Override
    public SalienceDecision score(RoleSpec spec, AgentEvent event) {
        String eventText = (event.kind().name() + " " + flatten(event)).toLowerCase(Locale.ROOT);
        double relevance = baseRelevance;

        int hits = 0;
        for (String keyword : spec.interestKeywords()) {
            if (keyword != null && !keyword.isBlank()
                    && eventText.contains(keyword.toLowerCase(Locale.ROOT))) {
                hits++;
            }
        }
        relevance += Math.min(KEYWORD_BONUS_CAP, keywordStep * hits);

        String skillText = String.join(" ", spec.skills()).toLowerCase(Locale.ROOT);
        if (!skillText.isBlank()) {
            for (String word : eventText.split("\\s+")) {
                if (word.length() > 1 && skillText.contains(word)) {
                    relevance += skillBonus;
                    break;
                }
            }
        }

        if (eventText.contains("urgent") || eventText.contains("critical") || eventText.contains("紧急")) {
            relevance += urgencyBonus;
        }
        relevance = Math.min(1.0, relevance);

        double score = event.priority().weight() / 10.0 * URGENCY_WEIGHT + relevance * RELEVANCE_WEIGHT;
        boolean pass = score >= spec.salienceThreshold();
        String reason = String.format(Locale.ROOT, "%s（score=%.2f, relevance=%.2f, 关键词命中=%d, 阈值=%.2f）",
                pass ? "通过" : "未通过", score, relevance, hits, spec.salienceThreshold());
        return new SalienceDecision(pass, score, relevance, reason);
    }

    /** 把 payload 压成一行可搜索文本（对齐 master 的 {@code String.valueOf(payload)}）。 */
    private static String flatten(AgentEvent event) {
        StringBuilder sb = new StringBuilder();
        event.payload().asMap().forEach((k, v) -> {
            sb.append(k).append('=').append(v).append(' ');
        });
        return Text.squashWhitespace(sb.toString());
    }
}
