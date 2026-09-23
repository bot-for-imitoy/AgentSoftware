package com.agent.software.tools.toolkits.hr;

import com.agent.software.role.Employee;
import com.agent.software.role.Role;
import com.agent.software.role.RoleFactory;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * post_job_posting：按一段招聘要求生成一名新员工的完整档案，并登记进公司员工名单。
 *
 * <p>默认只进名单（{@code OUT_OF_GROUP}），是否进组由 COO 的 {@code draft_in} 决定；
 * 传 {@code draft_in=true} 则生成后立刻入职当前大组。
 */
public class PostJobPosting extends Tool {

    private static final Logger logger = LoggerFactory.getLogger(PostJobPosting.class);

    private final Role role;

    public PostJobPosting(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "post_job_posting";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("requirement", "Job requirement in natural language (skills, seniority, personality).");
        schema.put("group", "optional target group; defaults to an inferred existing group");
        schema.put("draft_in", Map.of("type", "boolean",
                "description", "also draft the new employee into the current cohort (default false)"));
        return schema;
    }

    @Override
    public String getDescription() {
        return "Hire a new employee: generate their full profile from a requirement and register them "
                + "on the company roster (optionally draft them into the cohort immediately).";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || role.getSystem() == null) {
            return "post_job_posting error: role not bound to a system";
        }
        String requirement = args.get("requirement") == null ? "" : String.valueOf(args.get("requirement")).strip();
        if (requirement.isEmpty()) {
            return "post_job_posting error: needs requirement";
        }

        Employee employee;
        try {
            employee = new RoleFactory(role.getSystem()).createEmployee(requirement);
        } catch (Exception e) {
            logger.error("post_job_posting failed", e);
            return "post_job_posting error: hiring failed - " + e.getMessage();
        }

        String group = args.get("group") == null ? "" : String.valueOf(args.get("group")).strip();
        if (!group.isEmpty()) {
            employee.group = group;
            employee.template.put("group", group);
        }

        String status = "on the company roster (not in the cohort yet; the COO can draft_in)";
        boolean draftIn = args.get("draft_in") instanceof Boolean b && b;
        if (draftIn) {
            try {
                Role hired = role.getSystem().getStaffing().draftIn(employee.roleId);
                status = "joined the cohort and started work (" + hired.getState() + ")";
            } catch (Exception e) {
                logger.warn("post_job_posting: draft_in failed for {}", employee.roleId, e);
                status = "on the company roster; draft_in failed: " + e.getMessage();
            }
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("role_id", employee.roleId);
        info.put("name", employee.name);
        info.put("title", employee.templateString("title", ""));
        info.put("group", employee.group);
        info.put("responsibilities", employee.templateString("responsibilities", ""));
        info.put("personality", employee.templateString("personality", ""));
        List<String> skills = new ArrayList<>(employee.templateList("skills"));
        info.put("skills", skills);
        List<String> keywords = new ArrayList<>(employee.templateList("interest_keywords"));
        keywords.sort(String::compareTo);
        info.put("interest_keywords", keywords);
        info.put("membership", employee.membership.name());
        info.put("status", status);
        role.journal("Hired " + employee.roleId + " (" + employee.name + ")");
        return "post_job_posting: " + Json.stringifyPretty(info);
    }
}
