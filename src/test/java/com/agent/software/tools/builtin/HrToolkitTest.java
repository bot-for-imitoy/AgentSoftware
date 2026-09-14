package com.agent.software.tools.builtin;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.ToolService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HrToolkitTest {

    private static final RoleId HR = RoleId.of("HR");

    private static RoleSpec spec(String id, String name) {
        return new RoleSpec(RoleId.of(id), name, id, 1101, "Engineer", "", "",
                List.of("Rust", "gRPC", "PostgreSQL", "Docker", "K8s"), "", "", "", "podman",
                Payload.empty(), List.of("hr"));
    }

    private static ToolResult call(ToolService service, String tool, Map<String, Object> args) {
        return service.invoke(HR, new ToolCall("c", tool, Payload.of(args)));
    }

    @Test
    void postJobPostingCreatesAndOnboards() {
        AtomicReference<RoleSpec> hired = new AtomicReference<>();
        ToolService service = new ToolService();
        service.bind(HR, List.of(HrToolkit.create(
                requirement -> spec("rust_engineer", "Wu Xin"),
                hired::set,
                List::of)));

        ToolResult result = call(service, "post_job_posting", Map.of("requirement", "need a rust engineer"));
        assertTrue(result.ok(), result.text());
        assertTrue(result.text().contains("rust_engineer"));
        assertTrue(result.text().contains("joined the team"));
        assertEquals("Wu Xin", hired.get().name());
    }

    @Test
    void postJobPostingValidatesAndReportsBuilderFailures() {
        ToolService service = new ToolService();
        service.bind(HR, List.of(HrToolkit.create(
                requirement -> {
                    throw new IllegalStateException("model exploded");
                },
                r -> {
                },
                List::of)));

        assertFalse(call(service, "post_job_posting", Map.of()).ok());
        ToolResult failure = call(service, "post_job_posting", Map.of("requirement", "x"));
        assertFalse(failure.ok());
        assertTrue(failure.text().contains("model exploded"));
    }

    @Test
    void listCandidatesShowsTemplates() {
        List<RoleSpec> candidates = new ArrayList<>(List.of(spec("rust_engineer", "Wu Xin")));
        ToolService service = new ToolService();
        service.bind(HR, List.of(HrToolkit.create(r -> spec("x", "y"), r -> {
        }, () -> candidates)));

        ToolResult result = call(service, "list_candidates", Map.of());
        assertTrue(result.text().contains("rust_engineer"));
        assertTrue(result.text().contains("skills_count"));
    }
}
