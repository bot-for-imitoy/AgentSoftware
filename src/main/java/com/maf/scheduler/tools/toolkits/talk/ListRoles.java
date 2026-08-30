package com.maf.scheduler.tools.toolkits.talk;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.RolePool;
import com.maf.scheduler.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * list_roles — 获取当前团队都有哪些成员 (姓名/职责/技能/所属分组).
 * 在向同事发消息前, 或不确定该找谁处理某件事时, 先调用此工具.
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
            return "list_roles: (当前无团队成员)";
        }
        return "list_roles: 当前团队成员:\n" + roster;
    }

    /** 构建团队花名册 (固定格式, 供 talk 描述与 list_roles 工具复用). */
    public static String buildTeamRoster(RolePool pool) {
        List<String> rosterLines = new ArrayList<>();
        for (AgentRole r : pool.allRoles()) {
            String resp = !r.responsibilities.isEmpty() ? r.responsibilities : r.title;
            String group = (r.group == null ? "" : r.group).strip();
            if (group.isEmpty()) {
                group = "未分组";
            }
            List<String> skills = r.skills.size() > 4 ? r.skills.subList(0, 4) : r.skills;
            rosterLines.add("  - **" + r.name + "** -- " + resp + "  (组: " + group + ")  "
                    + "Skills: " + String.join(", ", skills));
        }
        return String.join("\n", rosterLines);
    }
}
