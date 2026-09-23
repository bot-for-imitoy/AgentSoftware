package com.agent.software.tools.toolkits.hr;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** HR 招聘工具包：post_job_posting（按需求生成新员工）/ list_candidates（看名单）。 */
public class Hr extends Toolkit {

    private final Role role;

    public Hr(Role role) {
        this.role = role;
        addTool(new PostJobPosting(role));
        addTool(new ListCandidates(role));
    }

    @Override
    public String getDescription() {
        return "HR toolkit: post_job_posting (create a new employee from a requirement), list_candidates";
    }
}
