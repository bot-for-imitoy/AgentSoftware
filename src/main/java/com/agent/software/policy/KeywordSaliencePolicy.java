package com.agent.software.policy;

import com.agent.software.model.AgentEvent;
import com.agent.software.model.RoleSpec;

/**
 * 关键词显著性策略（master {@code AgentRole.evaluateEvent} Layer 2 的等价物）。
 *
 * <p>打分 = 基础分 + 关键词命中 + 技能命中奖励 + 紧急词奖励，再与
 * {@link RoleSpec#salienceThreshold()} 比较。权重全部来自构造参数，便于调参。
 */
public final class KeywordSaliencePolicy implements SaliencePolicy {

    public KeywordSaliencePolicy(double baseRelevance, double keywordStep,
                                 double skillBonus, double urgencyBonus) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public SalienceDecision score(RoleSpec spec, AgentEvent event) {
        throw new UnsupportedOperationException("skeleton");
    }
}
