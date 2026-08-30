package com.maf.scheduler.tools.toolkits.skill;

import com.maf.scheduler.tools.SkillToolkit;
import com.maf.scheduler.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * skill_search — 搜索技能库中的技能 (按名称或描述关键词).
 * 先搜索找到合适技能, 再用 skill_add 添加给自己.
 */
public class SkillSearch extends Tool {

    private final SkillToolkit.SkillManager manager;

    public SkillSearch(SkillToolkit.SkillManager manager) {
        super();
        this.manager = manager;
    }

    @Override
    public String getToolName() {
        return "skill_search";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("keyword", "Search keyword, e.g. ppt/video/pdf/写作.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object okeyword = args.get("keyword");
        if (!(okeyword instanceof String)) {
            return okeyword == null
                    ? "skill_search: Error: needs keyword"
                    : "skill_search: Error: keyword is not a string";
        }
        String kw = ((String) okeyword).strip();
        if (kw.isEmpty()) {
            return "skill_search: Error: needs keyword";
        }
        List<Map<String, String>> hits = this.manager.searchSkills(kw);
        if (hits.isEmpty()) {
            return "skill_search: 没有匹配 '" + kw + "' 的技能. 可用 skill_list 查看全部.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("skill_search: 搜索 '" + kw + "' 找到 " + hits.size() + " 个技能:");
        for (Map<String, String> h : hits) {
            lines.add("- " + h.get("name") + ": " + h.get("description"));
        }
        return String.join("\n", lines);
    }
}
