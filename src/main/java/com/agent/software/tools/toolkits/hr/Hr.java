package com.agent.software.tools.toolkits.hr;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/**
 * Human resources toolkit (HR Toolkit) - hire-to-onboard:
 * post_job_posting / list_candidates.
 */
public class Hr extends Toolkit {

    private final Role role;
    private final String apiKey;

    public Hr(Role role, String apiKey) {
        this.role = role;
        this.apiKey = apiKey;
        addTool(new PostJobPosting(role, apiKey));
        addTool(new ListCandidates());
    }

    @Override
    public String getDescription(){
        return "HR toolkit: post job postings (hire-to-onboard), list candidates from the role template pool";
    }

}
