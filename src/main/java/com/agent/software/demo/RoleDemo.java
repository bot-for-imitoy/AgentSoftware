package com.agent.software.demo;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;

import java.util.List;
import java.util.Map;

/**
 * Role demo (Python version role_demo.py): single role creation + tool assembly + task execution.
 */
public final class RoleDemo {

    private static final String BOLD = "\033[1m";
    private static final String GREEN = "\033[32m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    private RoleDemo() {
    }

    private static void header(String text) {
        System.out.println("\n" + BOLD + CYAN + "═".repeat(60) + RESET);
        System.out.println(BOLD + CYAN + "  " + text + RESET);
        System.out.println(BOLD + CYAN + "═".repeat(60) + RESET + "\n");
    }

    public static void main(String[] args) {
        header("Multi-Role Concurrent Task Scheduler — OpenAI Integration");

        AgentRole coder = AgentRole.builder()
                .name("Li Ming")
                .roleId("coder")
                .title("Senior Backend Engineer")
                .personality("Rigorous and meticulous, pursues code quality, and is good at troubleshooting complex bugs")
                .skills(List.of("Python", "Go", "PostgreSQL", "Kubernetes", "Redis"))
                .interestKeywords(new java.util.LinkedHashSet<>(
                        List.of("bug", "fix", "crash", "500", "error", "debug", "race", "down")))
                .build();

        // Register the role and start
        RolePool pool = new RolePool();
        pool.addRole(coder);
        coder.onTaskStart = (role, task) -> System.out.println(
                "  [" + role.name + "] ▶ " + task.urgency + " — " + task.description);
        coder.onTaskDone = (role, task) -> System.out.println(
                "  [" + role.name + "] ✓ done (" + task.tokensConsumed + "t) — "
                        + (task.result.length() > 120 ? task.result.substring(0, 120) : task.result));
        pool.start();

        System.out.println("\n  Auto-registered tools: " + coder.mcpToolNames());

        // Assign a task directly
        pool.assignTask("coder", new AgentRole.Task(
                AgentRole.Urgency.HIGH.value,
                "Fix the 500 error on the login endpoint (POST /api/login NPE in UserService.verifyPassword)",
                "github", new java.util.LinkedHashMap<>()));

        // Wait for the task to execute (real LLM call, allow enough time)
        System.out.println("\n  " + GREEN + "Task queued, waiting for the worker to process... (real LLM call)" + RESET);
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Final status
        for (Map.Entry<String, Map<String, Object>> e : pool.getStatus().entrySet()) {
            Map<String, Object> s = e.getValue();
            System.out.println("  " + e.getKey() + ": busy=" + s.get("busy") + " queue=" + s.get("queue_depth"));
        }
        pool.shutdown(true);
        System.out.println("\n" + BOLD + GREEN + "Role Demo Complete." + RESET + "\n");
    }
}
