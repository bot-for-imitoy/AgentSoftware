package com.agent.software.tools.toolkits.hr;

import com.agent.software.role.AgentRole;
import com.agent.software.tools.Toolkit;

/**
 * 人力资源工具类 (HR Toolkit) — 招聘即入职:
 * post_job_posting / list_candidates.
 */
public class Hr extends Toolkit {

    private final AgentRole agentRole;
    private final String apiKey;

    public Hr(AgentRole agentRole, String apiKey) {
        this.agentRole = agentRole;
        this.apiKey = apiKey;
        addTool(new PostJobPosting(agentRole, apiKey));
        addTool(new ListCandidates());
    }

    @Override
    public String getDescription(){
        return "HR toolkit: post job postings (hire-to-onboard), list candidates from the role template pool";
    }

}
