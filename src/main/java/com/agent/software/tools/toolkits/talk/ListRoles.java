package com.agent.software.tools.toolkits.talk;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * list_roles — get the current team members (name/responsibilities/skills/group).
 * Call this tool before sending messages to colleagues, or when you are not sure who to ask about something.
 */
public class ListRoles extends Tool {

    private final RolePool pool;

    public ListRoles(RolePool pool) {
        super();
        this.pool = pool;
    }

    @Override
    public String getToolName() {
        return "list_roles";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        String roster = buildTeamRoster(pool);
        if (roster.isEmpty()) {
            return "list_roles: (no team members currently)";
        }
        return "list_roles: current team members:\n" + roster;
    }

    /** Build the team roster (fixed format, reused by the talk description and the list_roles tool). */
    public static String buildTeamRoster(RolePool pool) {
        List<String> rosterLines = new ArrayList<>();
        for (AgentRole r : pool.allRoles()) {
            String resp = !r.responsibilities.isEmpty() ? r.responsibilities : r.title;
            String group = (r.group == null ? "" : r.group).strip();
            if (group.isEmpty()) {
                group = "Unassigned";
            }
            List<String> skills = r.skills.size() > 4 ? r.skills.subList(0, 4) : r.skills;
            rosterLines.add("  - **" + r.name + "** -- " + resp + "  (Group: " + group + ")  "
                    + "Skills: " + String.join(", ", skills));
        }
        return String.join("\n", rosterLines);
    }
}
