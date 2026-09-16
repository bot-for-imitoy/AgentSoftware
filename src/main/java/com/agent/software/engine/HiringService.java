package com.agent.software.engine;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.model.RoleSpec;
import com.agent.software.ports.LlmClient;
import com.agent.software.ports.Recruiter;

import java.util.List;

/**
 * LLM 驱动的招聘（master {@code RoleFactory} + HR 工具的上岗流程）。
 *
 * <p>{@link #draft(String)} 只产出 {@link RoleSpec}（纯决策，不落库）；
 * {@link #onboard(RoleSpec)} 才真正注册并启动，是唯一产生副作用的动作。
 */
public final class HiringService implements Recruiter {

    private final LlmClient llm;
    private final List<RoleSpec> templates;
    private final Staffing staffing;

    public HiringService(LlmClient llm, List<RoleSpec> templates, Staffing staffing) {
        this.llm = llm;
        this.templates = templates;
        this.staffing = staffing;
    }

    @Override
    public List<RoleSpec> candidates() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public RoleSpec draft(String requirement) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public RoleId onboard(RoleSpec spec) {
        throw new UnsupportedOperationException("skeleton");
    }
}
