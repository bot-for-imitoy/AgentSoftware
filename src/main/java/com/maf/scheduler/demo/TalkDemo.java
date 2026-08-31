package com.maf.scheduler.demo;

import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.role.RolePool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通信演示 (Python 版 talk_demo.py): 演示多角色 talk 协作链.
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
                .name("李明").roleId("coder").title("Senior Backend Engineer")
                .personality("严谨细致，写完代码后主动找 reviewer 审查。遇到架构问题会咨询 architect。")
                .skills(List.of("Python", "Go", "PostgreSQL", "Kubernetes"))
                .interestKeywords(new java.util.LinkedHashSet<>(List.of("bug", "fix", "crash", "code", "implement")))
                .build();

        AgentRole reviewer = AgentRole.builder()
                .name("张伟").roleId("reviewer").title("Code Review Lead")
                .personality("审查代码时发现架构隐患会立即通知 architect。对安全问题零容忍。")
                .skills(List.of("Code Review", "Security Audit"))
                .interestKeywords(new java.util.LinkedHashSet<>(List.of("pr", "review", "security", "code")))
                .build();

        AgentRole architect = AgentRole.builder()
                .name("王建国").roleId("architect").title("System Architect")
                .personality("收到咨询后给出简洁方案。如果需要代码实现，会委托 coder 执行。")
                .skills(List.of("System Design", "Microservices", "DDD"))
                .interestKeywords(new java.util.LinkedHashSet<>(List.of("architecture", "design", "migration", "架构")))
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
                "我刚实现了一个 JWT refresh token 轮换功能。代码在 PR #188。\n"
                        + "请使用 talk 工具通知 reviewer 进行代码审查，urgency 设为 HIGH。\n"
                        + "先简单描述你实现了什么，然后调用 talk 发送审查请求。",
                "github", new LinkedHashMap<>()));

        sleep(20_000);

        header("Direct talk: Architect asks Coder a question");
        coder.talkTo("architect", "需要确认一下：新的 API gateway 应该用 REST 还是 gRPC？", "NORMAL");

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
