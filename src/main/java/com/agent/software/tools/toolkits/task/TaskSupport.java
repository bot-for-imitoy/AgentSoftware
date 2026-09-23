package com.agent.software.tools.toolkits.task;

import com.agent.software.AgentSystem;
import com.agent.software.event.Event;
import com.agent.software.event.EventBus;
import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import com.agent.software.event.TimeBus;
import com.agent.software.role.Role;
import com.agent.software.role.RolePool;
import com.agent.software.utils.Text;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code task} 工具包的共用逻辑：时间解析、任务 id 解析、一行式摘要。
 *
 * <p>时间只认两种写法（避免模型乱填）：
 * <ul>
 *   <li>{@code in_minutes}：从现在起 N 个模拟分钟；</li>
 *   <li>{@code day} + {@code tick}：绝对时刻，tick 是班次内的秒数（0 = 08:00，36000 = 18:00）。</li>
 * </ul>
 */
final class TaskSupport {

    /** 员工一天（tick）里的班次起点是 08:00，与 TimeBus 的显示口径一致。 */
    private static final long SHIFT_START_SECONDS = 8L * 3600L;
    private static final int SHORT_ID = 8;

    private TaskSupport() {
    }

    /** 解析好的排期时刻。 */
    record Slot(long tick, String when) {
    }

    // ── 基础类型 ────────────────────────────────────────────────

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static Integer toInt(Object o) {
        if (o instanceof Integer i) {
            return i;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && s.trim().matches("-?\\d+")) {
            return Integer.parseInt(s.trim());
        }
        return null;
    }

    static Priority priority(Object o) {
        String p = str(o).trim();
        return p.isEmpty() ? Priority.NORMAL : Priority.from(p);
    }

    /** integer 类型的参数声明（Tool.getInputSchema 认这种完整属性 Map）。 */
    static Map<String, Object> intProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "integer");
        p.put("description", description);
        return p;
    }

    static String shortId(String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return "????????";
        }
        return uuid.length() <= SHORT_ID ? uuid : uuid.substring(0, SHORT_ID);
    }

    // ── 时间 ────────────────────────────────────────────────────

    /**
     * 把参数解析成一个"未来的"绝对 tick。
     *
     * @param explicitDay 调用方是否显式给了 day；给了就必须是未来，没给则允许自动顺延到明天
     */
    static Slot resolveTime(TimeBus tb, Integer inMinutes, Integer day, Integer tick, boolean explicitDay) {
        if (tb == null) {
            throw new IllegalArgumentException("no clock available");
        }
        long now = tb.now();
        long dayTicks = tb.ticksPerDay();
        long shiftEnd = tb.getShiftEndTick();
        if (inMinutes != null) {
            if (inMinutes <= 0) {
                throw new IllegalArgumentException("in_minutes must be a positive number of minutes");
            }
            long target = now + inMinutes * 60L;
            return new Slot(target, describeTime(tb, target));
        }
        if (tick == null && day == null) {
            throw new IllegalArgumentException(
                    "needs a time: give in_minutes, or day + tick (tick 0 = 08:00, " + shiftEnd + " = 18:00)");
        }
        long tod = tick == null ? 0L : tick;
        if (tod < 0 || tod > shiftEnd) {
            throw new IllegalArgumentException(
                    "tick must be between 0 (08:00) and " + shiftEnd + " (18:00), got " + tod);
        }
        long targetDay = day == null ? (now / dayTicks) + 1 : day;
        if (targetDay < 1) {
            throw new IllegalArgumentException("day must be >= 1, got " + targetDay);
        }
        long target = (targetDay - 1) * dayTicks + tod;
        if (target <= now) {
            if (explicitDay) {
                throw new IllegalArgumentException("that time is already in the past: "
                        + describeTime(tb, target) + ", now it is " + tb.currentDateTime()
                        + " (day " + tb.getDay() + ", tick " + tb.getTickOfDay() + ")");
            }
            // 只给了 tick 且今天已经过了这个点 → 顺延到明天同时刻
            target += dayTicks;
        }
        return new Slot(target, describeTime(tb, target));
    }

    /** 把一个绝对 tick 描述成 "day N YYYY-MM-DD HH:MM (tick T)"。 */
    static String describeTime(TimeBus tb, long tick) {
        if (tb == null) {
            return "tick " + tick;
        }
        long dayTicks = tb.ticksPerDay();
        long day = Math.floorDiv(tick, dayTicks) + 1;
        long tod = Math.floorMod(tick, dayTicks);
        String date;
        try {
            date = tb.currentDate().plusDays(day - tb.getDay()).toString();
        } catch (Exception e) {
            date = "?";
        }
        String time = LocalTime.MIDNIGHT
                .plusSeconds(Math.floorMod(SHIFT_START_SECONDS + tod, 86_400L)).toString();
        return "day " + day + " " + date + " " + time + " (tick " + tod + ")";
    }

    // ── 排期表 ──────────────────────────────────────────────────

    /** 所有"还没到点"的排期任务，按时间排序。 */
    static List<Task> scheduledTasks(EventBus bus) {
        List<Task> out = new ArrayList<>();
        if (bus == null) {
            return out;
        }
        for (Event e : bus.scheduled()) {
            if (e instanceof Task t) {
                out.add(t);
            }
        }
        out.sort(Comparator.comparingLong(t -> t.targetTime));
        return out;
    }

    /** 按完整 id 或 id 前缀找排期任务；找不到或前缀有歧义时抛 IllegalArgumentException（面向模型）。 */
    static Task findScheduled(EventBus bus, String idOrPrefix) {
        String key = str(idOrPrefix).trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("needs a task_id (see list_tasks)");
        }
        List<Task> hits = new ArrayList<>();
        for (Task t : scheduledTasks(bus)) {
            if (t.uuid.equals(key) || t.uuid.startsWith(key)) {
                hits.add(t);
            }
        }
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("no scheduled task matches '" + key
                    + "' (already delivered tasks cannot be edited; see list_tasks / my_tasks)");
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException("task_id '" + key + "' is ambiguous ("
                    + hits.size() + " matches), use the full id");
        }
        return hits.get(0);
    }

    /** 一行摘要（给 list_tasks / my_tasks 用）。 */
    static String line(Task t, TimeBus tb) {
        return "- [" + shortId(t.uuid) + "] " + t.status
                + " | " + describeTime(tb, t.targetTime)
                + " | " + (t.fromRoleId == null || t.fromRoleId.isBlank() ? "system" : t.fromRoleId)
                + " -> " + (t.isBroadcast() ? "*" : t.targetRoleId)
                + " | " + t.priority
                + " | " + Text.truncate(t.content == null ? "" : t.content.replace('\n', ' '), 160);
    }

    /** 改/删一条排期任务的权限：创建者、被指派者、管理组。 */
    static void requireManageable(Role self, Task t) {
        if (self == null) {
            throw new IllegalArgumentException("no role");
        }
        if (self.roleId.equals(t.fromRoleId) || self.roleId.equals(t.targetRoleId)) {
            return;
        }
        RolePool pool = self.getSystem() == null ? null : self.getSystem().getRolePool();
        if (pool != null && pool.isManagement(self)) {
            return;
        }
        throw new IllegalArgumentException("task " + shortId(t.uuid) + " is "
                + (t.fromRoleId == null ? "system" : t.fromRoleId) + " -> " + t.targetRoleId
                + "; only its creator, its assignee or the management group may change it");
    }

    // ── 目标角色 ────────────────────────────────────────────────

    /**
     * 解析任务的目标角色：默认自己；指定别人时必须是当前大组成员，且服从 talk 的部门规则
     * （同组可指派，跨组只有管理组可以）。
     */
    static Role resolveTarget(Role self, String targetArg) {
        if (self == null || self.getSystem() == null) {
            throw new IllegalArgumentException("role not bound to a system");
        }
        String key = str(targetArg).trim();
        if (key.isEmpty() || "me".equalsIgnoreCase(key) || "self".equalsIgnoreCase(key)
                || self.roleId.equalsIgnoreCase(key)) {
            return self;
        }
        RolePool pool = self.getSystem().getRolePool();
        Role target = pool.find(key);
        if (target == null) {
            target = pool.findByName(key);
        }
        if (target == null) {
            throw new IllegalArgumentException("no active role '" + key
                    + "' in the cohort (roster employees are dormant until the COO drafts them in; "
                    + "see list_active)");
        }
        if (target != self && !self.canTalkTo(target)) {
            throw new IllegalArgumentException("you cannot assign tasks to " + target.roleId + " ("
                    + target.group + "): cross-team assignment is limited to the management group");
        }
        return target;
    }
}
