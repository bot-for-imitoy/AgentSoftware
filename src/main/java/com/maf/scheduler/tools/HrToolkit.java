package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.Json;
import com.maf.scheduler.core.RoleFactory;
import com.maf.scheduler.core.RolePool;
import com.maf.scheduler.core.RoleTemplates;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 人力资源工具类 (HR ToolKit) — Python 版 hr_toolkit.py.
 *
 * 包含: post_job_posting (招聘即入职) / list_candidates.
 */
public final class HrToolkit {

    private static final Logger logger = LoggerFactory.getLogger(HrToolkit.class);

    private HrToolkit() {
    }

    /** 创建人力资源工具类. */
    public static ToolKit createHrToolkit(String apiKey) {
        ToolKit tk = new ToolKit("hr", "人力资源工具类: 招聘, 入职");

        ToolHandler postJobPosting = args -> {
            String requirement = Json.str(args, "requirement", "").strip();
            if (requirement.isEmpty()) {
                return "错误: 'requirement' (用人需求) 为必填参数.";
            }
            RoleFactory factory = new RoleFactory(apiKey, null, null);
            AgentRole newRole;
            try {
                newRole = factory.createRole(requirement);
            } catch (Exception exc) {
                logger.error("招聘流程处理失败: {}", exc.getMessage());
                return "错误: 招聘启事处理失败 - " + exc.getMessage();
            }
            // 入职: 加入运行中的团队 (RolePool), 启动 worker
            AgentRole role = (AgentRole) tk.require("role", "角色");
            RolePool pool = role.pool();
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
            return Json.stringifyPretty(info);
        };

        ToolHandler listCandidates = args -> {
            List<Map<String, Object>> roles = new ArrayList<>();
            for (Map.Entry<String, Supplier<AgentRole>> e : RoleTemplates.TEMPLATES.entrySet()) {
                AgentRole r = e.getValue().get();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("role_id", r.roleId);
                m.put("name", r.name);
                m.put("title", r.title);
                m.put("skills_count", r.skills.size());
                roles.add(m);
            }
            return Json.stringifyPretty(roles);
        };

        Map<String, Object> postingSchema = new LinkedHashMap<>();
        postingSchema.put("type", "object");
        postingSchema.put("properties", Map.of(
                "requirement", TalkToolkit.mapOf("string", "用人需求描述 (自然语言, 尽量包含技能要求和性格偏好)")));
        postingSchema.put("required", List.of("requirement"));

        tk.addPythonTool("post_job_posting",
                "发布招聘启事. 输入用人需求, 发布后新员工会立即加入团队并上岗 "
                        + "(可收发消息、参与工作). 后台自动创建新员工的完整档案 "
                        + "(包括 role_id, 姓名, 职位, 性格, 技能, 关键词). "
                        + "示例需求: '需要一位精通 Rust 的后端工程师, 熟悉 gRPC 和 PostgreSQL'",
                postingSchema, postJobPosting);
        tk.addPythonTool("list_candidates",
                "列出当前角色模板池中的所有角色 (已入职的成员), 包含 role_id, 姓名, 职位.",
                TalkToolkit.emptySchema(), listCandidates);
        return tk;
    }

    /** 将当前角色绑定到 hr 工具类. */
    public static void bindRoleToToolkit(ToolKit toolkit, AgentRole role) {
        toolkit.bind("role", role);
    }
}
