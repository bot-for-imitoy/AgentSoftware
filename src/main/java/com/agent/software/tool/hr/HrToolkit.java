package com.agent.software.tool.hr;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.kernel.Text;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.util.ArrayList;
import java.util.List;

/**
 * 招聘工具包（id {@code "hr"}），暴露工具：post_job_posting / list_candidates。
 *
 * <p>工具层只做参数校验与结果排版，起草（LLM）与上岗（副作用）都委托给 {@link Recruiter}。
 */
public final class HrToolkit implements Toolkit {

    private final Recruiter recruiter;

    public HrToolkit(Recruiter recruiter) {
        this.recruiter = recruiter;
    }

    @Override
    public String id() {
        return "hr";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new PostJobPostingTool(), new ListCandidatesTool());
    }

    // ── post_job_posting ───────────────────────────────────────

    private final class PostJobPostingTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("post_job_posting",
                    "发布招聘需求：用 LLM 生成新角色档案并让其立即上岗（可收发消息、参与工作）。",
                    JsonSchema.object()
                            .string("requirement", "招聘需求描述（自然语言，尽量包含技能要求与性格偏好）。")
                            .required("requirement"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            String requirement = arguments.stringOr("requirement", "").trim();
            if (requirement.isEmpty()) {
                return ToolResult.error("post_job_posting：缺少 requirement 参数。");
            }
            RoleSpec draft;
            try {
                draft = recruiter.draft(requirement);
            } catch (DomainError e) {
                return ToolResult.error("post_job_posting：招聘处理失败 - " + e.getMessage());
            }
            RoleId newId;
            try {
                newId = recruiter.onboard(draft);
            } catch (RuntimeException e) {
                return ToolResult.error("post_job_posting：新员工上岗失败 - " + e.getMessage());
            }
            List<String> lines = new ArrayList<>();
            lines.add("post_job_posting：新员工已上岗并开始工作（可收发消息）。");
            lines.add("  role_id: " + newId.value());
            lines.add("  姓名: " + draft.name());
            lines.add("  岗位: " + draft.title());
            lines.add("  职责: " + draft.responsibilities());
            List<String> skills = draft.skills() == null ? List.of() : draft.skills();
            lines.add("  技能: " + String.join("、", skills));
            return ToolResult.ok(String.join("\n", lines));
        }
    }

    // ── list_candidates ────────────────────────────────────────

    private final class ListCandidatesTool implements Tool {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("list_candidates",
                    "列出当前角色模板池（候选人清单）：role_id、姓名、岗位与技能数量。",
                    JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            List<RoleSpec> candidates = recruiter.candidates();
            if (candidates.isEmpty()) {
                return ToolResult.ok("list_candidates：暂无角色模板。");
            }
            List<String> lines = new ArrayList<>();
            lines.add("list_candidates：候选角色模板（" + candidates.size() + " 个）：");
            for (RoleSpec candidate : candidates) {
                int skillCount = candidate.skills() == null ? 0 : candidate.skills().size();
                lines.add("  - " + candidate.id().value() + " / " + candidate.name()
                        + " — " + Text.orEmpty(candidate.title())
                        + "（技能 " + skillCount + " 项）");
            }
            return ToolResult.ok(String.join("\n", lines));
        }
    }
}
