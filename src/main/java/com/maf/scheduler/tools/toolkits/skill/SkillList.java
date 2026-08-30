package com.maf.scheduler.tools.toolkits.skill;

import com.maf.scheduler.tools.SkillToolkit;
import com.maf.scheduler.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * skill_list — 列出技能库中全部可用技能 (名称 + 简述).
 */
public class SkillList extends Tool {

    private final SkillToolkit.SkillManager manager;

    public SkillList(SkillToolkit.SkillManager manager) {
        super();
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_list";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        List<Map<String, String>> avail = this.manager.listAvailable();
        if (avail.isEmpty()) {
            return "skill_list: 暂无可用技能 (技能库为空, 请确认 data/skills/ 存在).";
        }
        List<String> lines = new ArrayList<>();
        lines.add("skill_list: 技能库共有 " + avail.size() + " 个技能:");
        for (Map<String, String> a : avail) {
            lines.add("- " + a.get("name") + ": " + a.get("description"));
        }
        return String.join("\n", lines);
    }
}
