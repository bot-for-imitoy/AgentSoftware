package com.agent.software.agent.dialog;

import com.agent.software.agent.role.RoleSpec;
import com.agent.software.kernel.Text;
import com.agent.software.sim.clock.Clock;

import java.util.ArrayList;
import java.util.List;

/**
 * System Prompt 组装器。
 *
 * <p>从 master {@code AgentRole.buildSystemPrompt()}（约 55 行、混在角色对象里）抽出：
 * 只依赖只读的时钟与一份 {@link DailySummary}，因此可单测，也不让
 * {@code agent.Agent} 继续变胖。
 *
 * <p>注意这里不依赖 {@code tool.note.NoteBook}：agent 包只认自己声明的窄端口，
 * 不认任何具体工具包（实现在 tool 侧，见 PLAN §3 依赖规则）。
 *
 * <p>提示词正文与 master 保持英文（它是 LLM 行为的一部分，不是文档）。
 */
public final class SystemPrompt {

    private final Clock clock;
    private final DailySummary summaries;

    public SystemPrompt(Clock clock, DailySummary summaries) {
        this.clock = clock;
        this.summaries = summaries;
    }

    /** 只读时钟（{@code agent.Agent} 用它回答"今天第几天"）。 */
    public Clock clock() {
        return clock;
    }

    /** 组装该角色的完整 System Prompt（人设 / 时间 / 云盘与 Git 规则 / 昨日总结）。 */
    public String build(RoleSpec spec) {
        List<String> parts = new ArrayList<>();
        parts.add("You are " + spec.name() + ", your title is " + spec.title()
                + ", working as the " + spec.id().value() + " role.");
        parts.add("Personality: " + spec.personality() + ".");
        if (!spec.skills().isEmpty()) {
            parts.add("Skills: " + String.join(", ", spec.skills()) + ".");
        }
        parts.add("Today is " + clock.currentDateTime().substring(0, 10)
                + " (day " + clock.nowDay().day() + "), company shift "
                + clock.calendar().shiftStartHour() + ":00–" + clock.calendar().shiftEndHour()
                + ":00 (1 tick = " + clock.calendar().secondsPerTick() + " simulated second(s), "
                + "tick 0 of the shift = 08:00:00).");
        parts.add("If you currently have no task, you may directly rest. "
                + "Also note: do not send messages to others when you should not be disturbing them; "
                + "only send when necessary. So when you have no task, do not ask others anything, "
                + "just rest. You will be notified automatically when something comes up. "
                + "After you finish a task, report the completion to the colleague who assigned it, then rest.");
        parts.add("If you have a task that involves communicating with someone, make sure to do it "
                + "at the scheduled time — not early, not late — because the other party expects you "
                + "to contact them at that time.");
        parts.add("The company cloud drive is at /mnt/drive (every computer mounts the same shared folder):\n"
                + "  - /mnt/drive/Public — public shared directory, readable and writable by all employees "
                + "(put shared resources, announcements, and collaboration files here)\n"
                + "  - /mnt/drive/" + spec.username() + " — your personal directory; only you can write to it; "
                + "other employees have read-only access\n"
                + "  - Other employees' personal directories are read-only for you as well\n"
                + "Use the computer's file commands directly for file operations (ls / cat / cp / mv / rm, etc.); "
                + "to share a file with a colleague: write it to Public, or send the cloud drive file path "
                + "via the talk attachment parameter.");
        parts.add("The company uses Git to manage project code (multi-person collaboration, multiple projects):\n"
                + "  - Each project is one repository; code is kept in its own repository per project\n"
                + "  - Run git commands on your personal computer (git clone / branch / add / commit / push / merge, etc.)\n"
                + "  - After completing a feature: first git pull to get the latest code, commit "
                + "(with a clear description of what and why), then push to merge into the main branch "
                + "or open a merge request\n"
                + "  - When collaborating with others on the same project, sync the latest code first (git pull) "
                + "to avoid conflicts; when a conflict occurs, communicate with the relevant colleagues "
                + "before merging\n"
                + "  - The main branch must always remain usable; do not force-overwrite others' code "
                + "without permission\n"
                + "For changes that need collaboration with colleagues, discuss the division of work first, "
                + "then commit and merge.");
        parts.add("Company email: every employee has a company mailbox (e.g. name@company.com), "
                + "and employees communicate via email (send_email to send / read_mail to receive).");
        if (spec.hasGroup()) {
            parts.add("You belong to the " + spec.group() + ", and your company email is " + mailAddress(spec) + ". "
                    + "Colleague communication rules: the talk tool can only message members of your own group "
                    + "(quick in-group communication); communication with colleagues in other groups "
                    + "(other teams, release management, leadership, etc.) must use email "
                    + "(send_email to send, read_mail to check the inbox).");
        }
        if (!Text.isBlank(spec.promptExtra())) {
            parts.add(spec.promptExtra());
        }
        // 注入"昨天"的总结（严格早于今天），对齐 master 的 getLatestSummary(dayNumber)
        if (summaries != null) {
            summaries.latestSummary(spec.id(), clock.nowDay().day()).ifPresent(summary -> {
                if (!summary.isBlank()) {
                    parts.add("\n[Yesterday's Summary]\n" + summary
                            + "\n(The above is yesterday's summary, for you to continue your work.)");
                }
            });
        }
        return String.join("\n", parts);
    }

    /**
     * 提示词里展示的公司邮箱。
     *
     * <p>优先用 {@link RoleSpec#email()}；没有显式邮箱时只能给一个示例域名——
     * 真实后缀属于 {@code AppConfig.Mail}，而本类刻意不依赖配置（见 PLAN §3）。
     */
    private static String mailAddress(RoleSpec spec) {
        return Text.isBlank(spec.email())
                ? spec.username() + "@company.com"
                : spec.email();
    }
}
