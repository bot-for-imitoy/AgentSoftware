package com.maf.scheduler.demo;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.RolePool;
import com.maf.scheduler.core.RoleTemplates;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 角色演示 (Python 版 role_demo.py): 单角色创建 + 工具装配 + 任务执行.
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
        header("Multi-Role Concurrent Task Scheduler — DeepSeek Integration");

        AgentRole coder = AgentRole.builder()
                .name("李明")
                .roleId("coder")
                .title("Senior Backend Engineer")
                .personality("严谨细致，追求代码质量，善于排查复杂 bug")
                .skills(List.of("Python", "Go", "PostgreSQL", "Kubernetes", "Redis"))
                .interestKeywords(new java.util.LinkedHashSet<>(
                        List.of("bug", "fix", "crash", "500", "error", "debug", "race", "down")))
                .build();

        // 注册角色并启动
        RolePool pool = new RolePool();
        pool.addRole(coder);
        coder.onTaskStart = (role, task) -> System.out.println(
                "  [" + role.name + "] ▶ " + task.urgency + " — " + task.description);
        coder.onTaskDone = (role, task) -> System.out.println(
                "  [" + role.name + "] ✓ done (" + task.tokensConsumed + "t) — "
                        + (task.result.length() > 120 ? task.result.substring(0, 120) : task.result));
        pool.start();

        System.out.println("\n  自动注册的工具: " + coder.mcpToolNames());

        // 直接派一个任务
        pool.assignTask("coder", new AgentRole.Task(
                AgentRole.Urgency.HIGH.value,
                "修复登录接口 500 错误 (POST /api/login NPE in UserService.verifyPassword)",
                "github", new java.util.LinkedHashMap<>()));

        // 等待任务执行 (真实 LLM 调用, 给足时间)
        System.out.println("\n  " + GREEN + "任务已入队, 等待 worker 处理... (真实 LLM 调用)" + RESET);
        try {
            Thread.sleep(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 最终状态
        for (Map.Entry<String, Map<String, Object>> e : pool.getStatus().entrySet()) {
            Map<String, Object> s = e.getValue();
            System.out.println("  " + e.getKey() + ": busy=" + s.get("busy") + " queue=" + s.get("queue_depth"));
        }
        pool.shutdown(true);
        System.out.println("\n" + BOLD + GREEN + "Role Demo Complete." + RESET + "\n");
    }
}
