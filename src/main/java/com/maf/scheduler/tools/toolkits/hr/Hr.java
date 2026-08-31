package com.maf.scheduler.tools.toolkits.hr;

import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.tools.Toolkit;

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
        return "人力资源工具类: 发布招聘启事 (招聘即入职), 列出角色模板池中的候选人";
    }

}
