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
 * post_job_posting — 发布招聘启事. 输入用人需求, 发布后新员工会立即
 * 加入团队并上岗 (可收发消息、参与工作). 后台自动创建新员工的完整档案.
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
        schema.put("requirement", "用人需求描述 (自然语言, 尽量包含技能要求和性格偏好).");
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
        RoleFactory factory = new RoleFactory(apiKey, null, null);
        AgentRole newRole;
        try {
            newRole = factory.createRole(requirement);
        } catch (Exception exc) {
            logger.error("招聘流程处理失败: {}", exc.getMessage());
            return "post_job_posting: Error: 招聘启事处理失败 - " + exc.getMessage();
        }
        // 入职: 加入运行中的团队 (RolePool), 启动 worker
        RolePool pool = agentRole.pool();
        String onboarding = "已加入团队";
        if (pool != null) {
            try {
                pool.addRoleAndStart(newRole);
                onboarding = "已加入团队并上岗 (可收发消息)";
            } catch (IllegalArgumentException exc) {
                logger.warn("入职失败: {}", exc.getMessage());
                onboarding = "团队注册失败: " + exc.getMessage();
            }
        } else {
            logger.warn("RolePool 未绑定, 新员工仅注册到模板池");
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
