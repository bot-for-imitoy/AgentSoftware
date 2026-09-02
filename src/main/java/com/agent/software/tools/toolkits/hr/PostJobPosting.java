package com.agent.software.tools.toolkits.hr;

import com.agent.software.role.AgentRole;

import com.agent.software.role.RoleFactory;
import com.agent.software.role.RolePool;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * post_job_posting - post a job posting. Enter the hiring requirement; once posted, the new employee immediately
 * joins the team and starts work (can send/receive messages, participate in work). The backend automatically creates the new employee's full profile.
 */
public class PostJobPosting extends Tool {

    private static final Logger logger = LoggerFactory.getLogger(PostJobPosting.class);

    private final AgentRole agentRole;
    private final String apiKey;

    public PostJobPosting(AgentRole agentRole, String apiKey) {
        super();
        this.agentRole = agentRole;
        this.apiKey = apiKey;
    }

    @Override
    public String getToolName() {
        return "post_job_posting";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("requirement", "Job requirement description (natural language; include skill requirements and personality preferences if possible).");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object oreq = args.get("requirement");
        if (!(oreq instanceof String)) {
            return oreq == null
                    ? "post_job_posting: Error: needs requirement"
                    : "post_job_posting: Error: requirement is not a string";
        }
        String requirement = ((String) oreq).strip();
        if (requirement.isEmpty()) {
            return "post_job_posting: Error: needs requirement";
        }
        RoleFactory factory = new RoleFactory(apiKey, null);
        AgentRole newRole;
        try {
            newRole = factory.createRole(requirement);
        } catch (Exception exc) {
            logger.error("Job posting processing failed: {}", exc.getMessage());
            return "post_job_posting: Error: job posting processing failed - " + exc.getMessage();
        }
        // onboarding: join the running team (RolePool), start the worker
        RolePool pool = agentRole.pool();
        String onboarding = "Joined the team";
        if (pool != null) {
            try {
                pool.addRoleAndStart(newRole);
                onboarding = "Joined the team and started work (can send/receive messages)";
            } catch (IllegalArgumentException exc) {
                logger.warn("Onboarding failed: {}", exc.getMessage());
                onboarding = "Team registration failed: " + exc.getMessage();
            }
        } else {
            logger.warn("RolePool not bound, the new employee is only registered in the template pool");
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("role_id", newRole.roleId);
        info.put("name", newRole.name);
        info.put("title", newRole.title);
        info.put("responsibilities", newRole.responsibilities);
        info.put("personality", newRole.personality);
        info.put("skills", new ArrayList<>(newRole.skills));
        List<String> keywords = new ArrayList<>(newRole.interestKeywords);
        keywords.sort(String::compareTo);
        info.put("interest_keywords", keywords);
        info.put("status", onboarding);
        return "post_job_posting: " + Json.stringifyPretty(info);
    }
}
