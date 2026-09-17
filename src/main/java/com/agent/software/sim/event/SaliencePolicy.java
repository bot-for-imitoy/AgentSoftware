package com.agent.software.sim.event;

import com.agent.software.agent.role.RoleSpec;

/**
 * 内容相关性打分（master {@code AgentRole.evaluateEvent} 的 Layer 2）。
 *
 * <p>只对广播事件生效；定向事件不做内容过滤。纯函数，便于单测与调参。
 */
public interface SaliencePolicy {

    SalienceDecision score(RoleSpec spec, AgentEvent event);

    record SalienceDecision(boolean pass, double score, double relevance, String reason) {
    }
}
