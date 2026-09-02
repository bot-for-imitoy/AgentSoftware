package com.agent.software.demo;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talk demo (Python version talk_demo.py): demonstrates multi-role talk collaboration chains.
 */
public final class TalkDemo {

    private static final String BOLD = "\033[1m";
    private static final String GREEN = "\033[32m";
    private static final String CYAN = "\033[36m";
    private static final String BLUE = "\033[34m";
    private static final String MAGENTA = "\033[35m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    private TalkDemo() {
    }

    private static void header(String text) {
        System.out.println("\n" + BOLD + CYAN + "═".repeat(60) + RESET);
        System.out.println(BOLD + CYAN + "  " + text + RESET);
        System.out.println(BOLD + CYAN + "═".repeat(60) + RESET + "\n");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        header("Inter-Role Communication — talk Tool Demo");

        AgentRole coder = AgentRole.builder()
                .name("Li Ming").roleId("coder").title("Senior Backend Engineer")
                .personality("Rigorous and meticulous, proactively asks the reviewer to review after writing code. Consults the architect when facing architecture problems.")
                .skills(List.of("Python", "Go", "PostgreSQL", "Kubernetes"))
                .interestKeywords(new java.util.LinkedHashSet<>(List.of("bug", "fix", "crash", "code", "implement")))
                .build();

        AgentRole reviewer = AgentRole.builder()
                .name("Zhang Wei").roleId("reviewer").title("Code Review Lead")
                .personality("Immediately notifies the architect when a review uncovers architecture risks. Zero tolerance for security issues.")
                .skills(List.of("Code Review", "Security Audit"))
                .interestKeywords(new java.util.LinkedHashSet<>(List.of("pr", "review", "security", "code")))
                .build();

        AgentRole architect = AgentRole.builder()
                .name("Wang Jianguo").roleId("architect").title("System Architect")
                .personality("Gives concise solutions when consulted. If code implementation is needed, delegates the execution to the coder.")
                .skills(List.of("System Design", "Microservices", "DDD"))
                .interestKeywords(new java.util.LinkedHashSet<>(List.of("architecture", "design", "migration", "architecture")))
                .build();

        RolePool pool = new RolePool();
        pool.addRole(coder);
        pool.addRole(reviewer);
        pool.addRole(architect);

        AgentRole.TaskCallback onStart = (role, task) ->
                System.out.println("  " + BLUE + "[" + role.name + "]" + RESET + " "
                        + YELLOW + "▶ " + AgentRole.Urgency.from(task.urgency).name() + RESET
                        + " — " + (task.description.length() > 100 ? task.description.substring(0, 100) : task.description));
        AgentRole.TaskCallback onDone = (role, task) -> {
            String icon = "done".equals(task.status) ? GREEN + "✓" + RESET : RED + "✗" + RESET;
            String preview = task.result.length() > 200 ? task.result.substring(0, 200) : task.result;
            System.out.println("  " + BLUE + "[" + role.name + "]" + RESET + " " + icon + " done ("
                    + task.tokensConsumed + "t)");
            System.out.println("  " + MAGENTA + "→" + RESET + " " + preview.replace("\n", " ") + "...");
        };
        for (AgentRole r : List.of(coder, reviewer, architect)) {
            r.onTaskStart = onStart;
            r.onTaskDone = onDone;
        }

        pool.start();

        Map<String, List<String>> toolsSummary = new LinkedHashMap<>();
        for (AgentRole r : pool.allRoles()) {
            toolsSummary.put(r.roleId, r.mcpToolNames());
        }
        System.out.println("\n  " + GREEN + "Auto-registered tools per role:" + RESET);
        for (Map.Entry<String, List<String>> e : toolsSummary.entrySet()) {
            System.out.println("    " + e.getKey() + ": " + e.getValue());
        }

        header("Collaboration Chain: Coder → Reviewer → Architect → Coder");
        System.out.println("  " + YELLOW + "Starting: Coder implements a feature, should ask reviewer to review" + RESET + "\n");

        pool.assignTask("coder", new AgentRole.Task(AgentRole.Urgency.HIGH.value,
                "I just implemented a JWT refresh token rotation feature. The code is in PR #188.\n"
                        + "Please use the talk tool to notify the reviewer to do a code review, and set urgency to HIGH.\n"
                        + "First briefly describe what you implemented, then call talk to send the review request.",
                "github", new LinkedHashMap<>()));

        sleep(20_000);

        header("Direct talk: Architect asks Coder a question");
        coder.talkTo("architect", "Need to confirm: should the new API gateway use REST or gRPC?", "NORMAL");

        sleep(10_000);

        header("Final Status");
        for (Map.Entry<String, Map<String, Object>> e : pool.getStatus().entrySet()) {
            Map<String, Object> s = e.getValue();
            System.out.println("  " + String.format("%-12s", e.getKey()) + " busy=" + s.get("busy")
                    + "  queue=" + s.get("queue_depth"));
        }

        pool.shutdown(true);
        System.out.println("\n" + BOLD + GREEN + "Talk Demo Complete." + RESET + "\n");
    }
}
