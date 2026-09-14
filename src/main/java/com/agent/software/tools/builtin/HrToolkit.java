package com.agent.software.tools.builtin;

import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.Tool;
import com.agent.software.tools.spi.Toolkit;
import com.agent.software.tools.spi.Tools;
import com.agent.software.utils.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The {@code hr} toolkit: publish a job posting (which creates and onboards a new
 * employee) and list the available role templates.
 */
public final class HrToolkit {

    private HrToolkit() {
    }

    /**
     * @param roleBuilder turns a hiring requirement into a new role
     * @param onHire      receives the created role so the runtime can start it
     * @param candidates  the role templates available for hire
     */
    public static Toolkit create(Function<String, RoleSpec> roleBuilder,
                                 Consumer<RoleSpec> onHire,
                                 Supplier<List<RoleSpec>> candidates) {
        Tool post = Tools.of("post_job_posting",
                "Publish a job posting; the new colleague is onboarded and starts working immediately",
                JsonSchema.builder()
                        .required("requirement", JsonSchema.Property.string(
                                "Job requirement description (skills and personality preferences)."))
                        .build(),
                (role, call) -> {
                    String requirement = Tools.argStripped(call, "requirement");
                    if (requirement.isEmpty()) {
                        return ToolResult.error("post_job_posting: Error: needs requirement");
                    }
                    RoleSpec created;
                    try {
                        created = roleBuilder.apply(requirement);
                    } catch (RuntimeException e) {
                        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        return ToolResult.error("post_job_posting: Error: " + message);
                    }
                    String status = "created";
                    if (onHire != null) {
                        try {
                            onHire.accept(created);
                            status = "joined the team and started working";
                        } catch (RuntimeException e) {
                            status = "team registration failed: " + e.getMessage();
                        }
                    }
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("role_id", created.id().value());
                    info.put("name", created.name());
                    info.put("title", created.title());
                    info.put("skills", created.skills());
                    info.put("status", status);
                    return ToolResult.success("post_job_posting: " + Json.stringifyPretty(info));
                });

        Tool list = Tools.of("list_candidates", "List the role templates available for hire",
                JsonSchema.object(),
                (role, call) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (RoleSpec spec : candidates.get()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("role_id", spec.id().value());
                        m.put("name", spec.name());
                        m.put("title", spec.title());
                        m.put("skills_count", spec.skills().size());
                        out.add(m);
                    }
                    return ToolResult.success("list_candidates: " + Json.stringifyPretty(out));
                });

        return new Toolkit("hr", "HR toolkit: publish job postings and list candidates",
                List.of(post, list));
    }
}
