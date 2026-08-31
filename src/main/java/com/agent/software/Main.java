package com.agent.software;

import com.agent.software.tools.toolkits.client.Client;
import com.agent.software.tools.toolkits.hr.Hr;
import com.agent.software.role.AgentRole;
import com.agent.software.store.NoteStore;
import com.agent.software.role.RoleTemplates;
import com.agent.software.store.StateStore;
import com.agent.software.core.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 系统主入口 (Python 版 main.py 的 Java 对应物).
 *
 * 启动完整的多角色 AI 团队模拟: 恢复进度 → 循环跑日 (上班 → 派任务 → 下班)
 * → 保存状态.
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    // ── 终端配色 ─────────────────────────────────────────────
    private static final String BOLD = "\033[1m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String RESET = "\033[0m";

    // ── 时间参数 (真实时间, 分钟/小时) ───────────────────────
    private static final int TICK_MINUTES = 10;
    private static final int TICK1_MINUTES = 10;
    private static final int SHIFT_END_HOURS = 10;
    private static final int DAY_BOUNDARY_HOURS = 24;

    private static final SortedSet<String> ROLE_IDS = new TreeSet<>(RoleTemplates.DEFAULT_ROLES);

    // ── 终端 UI ─────────────────────────────────────────────

    static void header(String text) {
        consolePrint("\n" + BOLD + CYAN + "═".repeat(62) + RESET);
        consolePrint(BOLD + CYAN + "  " + text + RESET);
        consolePrint(BOLD + CYAN + "═".repeat(62) + RESET + "\n");
    }

    static void step(String text) {
        consolePrint(MAGENTA + "▶ " + text + RESET);
    }

    static void info(String text) {
        consolePrint("  " + text);
    }

    static void ok(String text) {
        consolePrint("  " + GREEN + "✓ " + text + RESET);
    }

    static void warn(String text) {
        consolePrint("  " + YELLOW + "⚠ " + text + RESET);
    }

    private static void consolePrint(String msg) {
        System.out.println(msg);
    }

    /** 轮询等待条件满足 (真实时间). 返回 true=满足, false=超时. */
    static boolean waitUntil(String desc, java.util.function.Supplier<Boolean> predicate,
                             long timeoutSeconds) {
        info("等待: " + desc + " (最长 " + (timeoutSeconds / 60) + " 分钟)...");
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(predicate.get())) {
                    ok(desc + " ✓");
                    return true;
                }
            } catch (Exception ignored) {
            }
            sleep(5000);
        }
        warn("等待超时: " + desc);
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 运行一天的完整流程 (真实时间, 由 TimeEventBus 自动走时). */
    static void runOneDay(AgentSystem system, int day, boolean withClientTask) {
        header("第 " + day + " 天");

        // 新的一天: 等待跨天边界 (day_number 变化 → SHIFT_START 自动触发)
        if (day > 1) {
            int targetDay = day;
            waitUntil("第 " + day + " 天开始 (约 " + DAY_BOUNDARY_HOURS * (day - 1)
                            + " 小时后, SHIFT_START 自动触发)",
                    () -> system.day() >= targetDay,
                    DAY_BOUNDARY_HOURS * 3600L);
        }
        ok("当前: " + system.describe());

        // 第 1 天: CEO 与甲方沟通 (仅此一次)
        if (withClientTask) {
            step("CEO 注册开局笔记提醒: Tick 1 (10 分钟后) 与用户沟通项目要求...");
            AgentRole ceo = system.getRole("CEO");
            Path note = ceo.noteStore().writeNote("第1天-收集项目需求",
                    "与用户沟通项目要求, 收集今天要开发的项目需求", 1, day);
            ok("笔记+提醒已注册: " + note + " (第 " + day + " 天 Tick 1 → CEO)");
            step("等待 Tick 1 触发 (CEO 任务 → 与用户沟通)...");
            int fireTick = (day - 1) * 144 + 1;
            waitUntil("Tick " + fireTick + " 到达 (CEO 任务触发)",
                    () -> system.timeManager.currentTick() >= fireTick,
                    (TICK1_MINUTES + 5) * 60L);
            info("请在上方 [CEO] 提示处输入项目要求 (例如: 帮我开发一个支付系统)");
            sleep(10_000);
        } else {
            step("今天没有甲方沟通任务, 直接进入日常工作...");
            sleep(5_000);
        }

        // 白天工作事件
        step("投递 LOW 事件 (闲聊, 应被显著性过滤, 0 Token)...");
        Map<String, Object> spamPayload = new java.util.LinkedHashMap<>();
        spamPayload.put("text", "中午吃什么?");
        spamPayload.put("channel", "#random");
        Types.Event spam = new Types.Event("slack", "chat", Types.Priority.LOW, spamPayload, null);
        Map<String, Map<String, Object>> results = system.trigger(spam);
        Map<String, Boolean> acceptedMap = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : results.entrySet()) {
            acceptedMap.put(e.getKey(), (Boolean) e.getValue().get("accepted"));
        }
        info("LOW 过滤结果: " + acceptedMap);

        // 等待下班 (Tick 60 = 10 小时后, SHIFT_END 自动触发)
        step("等待下班... (Tick 60 = 10 小时后, SHIFT_END 自动触发)");
        waitUntil("下班时刻到达 (SHIFT_END 触发)",
                () -> system.timeManager.tickOfDay() >= 60,
                (SHIFT_END_HOURS + 1) * 3600L);
        sleep(5_000);

        step("等待角色调用 summary 工具 (并发, 最长 600 秒)...");
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allOff = true;
            for (String rid : ROLE_IDS) {
                if (system.getRole(rid).state != Types.AgentState.OFF_DUTY) {
                    allOff = false;
                    break;
                }
            }
            if (allOff) {
                break;
            }
            sleep(5_000);
        }

        // 检查下班状态与总结
        step("检查下班状态...");
        List<String> offDuty = new ArrayList<>();
        for (String rid : ROLE_IDS) {
            if (system.getRole(rid).state == Types.AgentState.OFF_DUTY) {
                offDuty.add(rid);
            }
        }
        if (!offDuty.isEmpty()) {
            List<String> head = offDuty.size() > 6 ? offDuty.subList(0, 6) : offDuty;
            ok("OFF_DUTY 角色: " + offDuty.size() + "/" + ROLE_IDS.size()
                    + " (" + String.join(", ", head) + (offDuty.size() > 6 ? "..." : "") + ")");
        } else {
            warn("角色仍未全部 OFF_DUTY");
        }

        for (String rid : ROLE_IDS) {
            AgentRole role = system.getRole(rid);
            String summary = role.noteStore().getSummary(day);
            if (summary == null) {
                // summary 工具保存后立即关机, 直接读宿主机挂载目录
                String hostDir = role.computerIfCreated() != null ? role.computerIfCreated().hostDir() : "";
                if (!hostDir.isEmpty()) {
                    Path hostSummary = Paths.get(hostDir, "summaries", NoteStore.summaryFilename(day));
                    if (Files.exists(hostSummary)) {
                        try {
                            summary = Files.readString(hostSummary);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (summary != null && !summary.isEmpty()) {
                ok("[" + rid + "] 第" + day + "天总结已保存: "
                        + (summary.length() > 50 ? summary.substring(0, 50) : summary) + "...");
            } else {
                info("[" + rid + "] 暂无总结");
            }
        }
    }

    public static void main(String[] args) {
        header("作息系统演示 — 真实时间流动 (1 Tick = 10 分钟)");

        // 1. 开局: 默认团队 (管理层 + 工程团队)
        List<String> roleIds = new ArrayList<>(ROLE_IDS);
        step("创建 AgentSystem, 加入 " + roleIds.size() + " 个默认角色...");
        AgentSystem system = new AgentSystem(null, roleIds, 30.0, true);
        system.getRole("CEO").addToolkit(new Client(system.getRole("CEO")));
        system.getRole("HR").addToolkit(new Hr(system.getRole("HR"), null));
        ok("角色就绪: " + system.pool.listRoles().size() + " 人 (CEO/COO/HR + 工程团队)");
        ok("CEO 已装备 talk_to_client (与甲方实时交流)");
        ok("HR 已装备招聘工具 (post_job_posting / list_candidates)");

        // 0. 恢复上次进度 (StateStore)
        StateStore store = new StateStore();
        int restored = store.exists() ? store.restore(system) : 0;
        if (restored > 0) {
            ok("已从存档恢复 " + restored + " 个角色 → " + system.describe());
        } else {
            ok("无存档, 从第 1 天 Tick 0 开始");
        }

        // 2. 启动系统
        system.start();
        ok("系统已启动: " + system.describe());
        ok("时间规则: 1 Tick = " + TICK_MINUTES + " 分钟; 下班 = " + SHIFT_END_HOURS
                + " 小时后; 第 2 天 = " + DAY_BOUNDARY_HOURS + " 小时后");
        sleep(3_000);  // 等 SHIFT_START (Tick 0) 触发
        info("角色状态: " + states(system));

        // 3. 多日循环
        int day = system.day();
        try {
            while (true) {
                runOneDay(system, day, day == 1);
                // 一天结束: 自动进入第二天
                int nextDay = day + 1;
                consolePrint("\n" + BOLD + GREEN + "═".repeat(62) + RESET);
                consolePrint(BOLD + GREEN + "  🎉 第 " + day + " 天结束!" + RESET);
                consolePrint(BOLD + GREEN + "  已自动进入第 " + nextDay + " 天: 将于约 "
                        + (DAY_BOUNDARY_HOURS - SHIFT_END_HOURS) + " 小时后上班 "
                        + "(SHIFT_START 自动触发)" + RESET);
                consolePrint(BOLD + GREEN + "═".repeat(62) + RESET + "\n");
                day = nextDay;
            }
        } catch (Exception e) {
            warn("\n收到中断, 正在保存进度并退出...");
        } finally {
            // 退出自动保存
            try {
                store.save(system);
            } catch (Exception e) {
                logger.error("保存状态失败", e);
            }
            system.stop();
            consolePrint("\n" + BOLD + GREEN + "演示结束 ✓ (运行到第 " + day + " 天, 进度已保存)" + RESET + "\n");
        }
    }

    private static Map<String, String> states(AgentSystem system) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String rid : ROLE_IDS) {
            m.put(rid, system.getRole(rid).state.value);
        }
        return m;
    }
}
