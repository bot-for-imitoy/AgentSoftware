package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.computers.Computer;
import com.maf.scheduler.utils.Json;
import com.maf.scheduler.core.NoteStore;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
import com.maf.scheduler.core.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆工具类 (Memory ToolKit) — Python 版 memory_toolkit.py.
 *
 * 包含: summary / write_note / edit_note / list_notes / read_note.
 */
public final class MemoryToolkit {

    private static final Logger logger = LoggerFactory.getLogger(MemoryToolkit.class);

    private MemoryToolkit() {
    }

    /** 创建记忆工具类. */
    public static ToolKit createMemoryToolkit() {
        ToolKit tk = new ToolKit("memory", "记忆与笔记工具类: 每日总结, 笔记管理");

        ToolHandler summary = args -> {
            String content = Json.str(args, "content", "").strip();
            if (content.isEmpty()) {
                return "错误: 'content' (总结内容) 为必填参数.";
            }
            NoteStore store = (NoteStore) tk.require("store", "笔记存储");
            AgentRole role = (AgentRole) tk.get("role", null);
            // 天序号: 显式传入或取角色当前天
            Object dayObj = args.get("day");
            Integer day = dayObj instanceof Number n ? n.intValue() : null;
            if (day == null && role != null) {
                day = role.timeManager().dayNumber();
            }
            if (day == null) {
                day = 1;
            }
            java.nio.file.Path path = store.saveSummary(content, day);
            if (role != null) {
                role.journal("保存第 " + day + " 天总结 (" + content.length() + " 字符)");
            }
            // 总结完成 → 角色下班 (OFF_DUTY) + 一天结束自动关电脑
            if (role != null) {
                if (role.state != Types.AgentState.OFF_DUTY) {
                    role.setState(Types.AgentState.OFF_DUTY);
                    logger.info("[{}] 总结完成, 角色已切换为 OFF_DUTY", role.roleId);
                }
                try {
                    Computer comp = role.computerIfCreated();
                    if (comp != null && comp.isOn()) {
                        comp.powerOff();
                        logger.info("[{}] 一天结束, 电脑已自动关机", role.roleId);
                    }
                } catch (Exception e) {
                    logger.warn("[{}] 电脑自动关机失败", role.roleId);
                }
                return "第 " + day + " 天总结已保存: " + path + ". 你已下班 (OFF_DUTY), 电脑已关闭.";
            }
            return "第 " + day + " 天总结已保存: " + path;
        };

        ToolHandler writeNote = args -> {
            String title = Json.str(args, "title", "").strip();
            String content = Json.str(args, "content", "");
            Object remindTick = args.get("remind_tick");
            Object remindDay = args.get("remind_day");
            if (title.isEmpty()) {
                return "错误: 'title' (笔记标题) 为必填参数.";
            }
            NoteStore store = (NoteStore) tk.require("store", "笔记存储");
            AgentRole role = (AgentRole) tk.get("role", null);
            try {
                store.writeNote(title, content,
                        remindTick instanceof Number n ? n.intValue() : null,
                        remindDay instanceof Number n ? n.intValue() : null);
            } catch (IllegalArgumentException exc) {
                return "错误: " + exc.getMessage();
            }
            if (role != null) {
                String day = remindDay != null ? String.valueOf(remindDay) : String.valueOf(role.timeManager().dayNumber());
                role.journal("写入笔记: " + title
                        + (remindTick != null ? " (提醒: 第 " + day + " 天 Tick " + remindTick + ")" : ""));
            }
            if (remindTick != null) {
                Map<String, Object> r = store.getReminder(title);
                int d = r != null ? ((Number) r.get("day")).intValue() : (remindDay != null ? ((Number) remindDay).intValue() : 1);
                int t = r != null ? ((Number) r.get("tick")).intValue() : ((Number) remindTick).intValue();
                return "笔记已保存并设置提醒: " + title + " (将在第 " + d + " 天 Tick " + t + " 提醒你处理)";
            }
            return "笔记已保存: " + title;
        };

        ToolHandler editNote = args -> {
            String title = Json.str(args, "title", "").strip();
            String content = Json.str(args, "content", "");
            Object remindTick = args.get("remind_tick");
            Object remindDay = args.get("remind_day");
            if (title.isEmpty()) {
                return "错误: 'title' (笔记标题) 为必填参数.";
            }
            NoteStore store = (NoteStore) tk.require("store", "笔记存储");
            AgentRole role = (AgentRole) tk.get("role", null);
            try {
                store.editNote(title, content,
                        remindTick instanceof Number n ? n.intValue() : null,
                        remindDay instanceof Number n ? n.intValue() : null);
            } catch (IllegalArgumentException exc) {
                return "错误: " + exc.getMessage();
            }
            if (role != null) {
                role.journal("更新笔记: " + title);
            }
            if (remindTick != null) {
                Map<String, Object> r = store.getReminder(title);
                int d = r != null ? ((Number) r.get("day")).intValue() : (remindDay != null ? ((Number) remindDay).intValue() : 1);
                int t = r != null ? ((Number) r.get("tick")).intValue() : ((Number) remindTick).intValue();
                return "笔记已更新并重置提醒: " + title + " (将在第 " + d + " 天 Tick " + t + " 提醒你处理)";
            }
            return "笔记已更新: " + title;
        };

        ToolHandler listNotes = args -> {
            NoteStore store = (NoteStore) tk.require("store", "笔记存储");
            List<String> titles = store.listNotes();
            if (titles.isEmpty()) {
                return "(暂无笔记)";
            }
            List<String> lines = new ArrayList<>();
            for (String t : titles) {
                Map<String, Object> r = store.getReminder(t);
                if (r != null) {
                    lines.add("- " + t + " (提醒: 第 " + r.get("day") + " 天 Tick " + r.get("tick") + ")");
                } else {
                    lines.add("- " + t);
                }
            }
            return String.join("\n", lines);
        };

        ToolHandler readNote = args -> {
            String title = Json.str(args, "title", "").strip();
            if (title.isEmpty()) {
                return "错误: 'title' (笔记标题) 为必填参数.";
            }
            NoteStore store = (NoteStore) tk.require("store", "笔记存储");
            String content = store.readNote(title);
            if (content == null) {
                return "笔记不存在: " + title;
            }
            return content;
        };

        Map<String, Object> summarySchema = new LinkedHashMap<>();
        summarySchema.put("type", "object");
        summarySchema.put("properties", Map.of(
                "content", TalkToolkit.mapOf("string", "今日总结内容"),
                "day", TalkToolkit.mapOf("integer", "第几天 (可选, 默认当前天)")));
        summarySchema.put("required", List.of("content"));

        Map<String, Object> titleContentSchema = new LinkedHashMap<>();
        titleContentSchema.put("type", "object");
        titleContentSchema.put("properties", Map.of(
                "title", TalkToolkit.mapOf("string", "笔记标题"),
                "content", TalkToolkit.mapOf("string", "笔记内容 (待办/记录/计划等)"),
                "remind_tick", TalkToolkit.mapOf("integer", "提醒 Tick (可选, 0~60, 0=上班 60=下班; 填了则到点提醒你)"),
                "remind_day", TalkToolkit.mapOf("integer", "提醒触发天 (可选, 默认今天, 可设未来天)")));
        titleContentSchema.put("required", List.of("title", "content"));

        Map<String, Object> titleOnlySchema = new LinkedHashMap<>();
        titleOnlySchema.put("type", "object");
        titleOnlySchema.put("properties", Map.of(
                "title", TalkToolkit.mapOf("string", "笔记标题")));
        titleOnlySchema.put("required", List.of("title"));

        tk.addPythonTool("summary",
                "总结今天的工作. 调用此工具后, 总结内容会被保存, 并在下一天自动注入到你的系统提示词中. "
                        + "同时你会切换为 OFF_DUTY (下班) 状态. content 应包含: 今日完成的工作, 关键决策, 未完成事项.",
                summarySchema, summary);
        tk.addPythonTool("write_note",
                "写一篇笔记 (笔记与定时任务已统一). 用于记录信息 / 决策依据 / 待办事项. "
                        + "可填 remind_tick 设置提醒时间 (可选): 到点系统会像任务一样提醒你处理, "
                        + "不填就是普通笔记. "
                        + "示例: write_note('写周报', '本周工作小结', remind_tick=50) = 在 Tick 50 提醒我写周报.",
                titleContentSchema, writeNote);
        tk.addPythonTool("edit_note",
                "编辑已有笔记 (覆盖原内容). 笔记不存在时自动创建. "
                        + "提供 remind_tick 可重置提醒时间 (可选); 不提供则保留原提醒. "
                        + "删除提醒请用 delete_note.",
                titleContentSchema, editNote);
        tk.addPythonTool("list_notes",
                "列出当前所有笔记 (含提醒时间信息, 若有).",
                TalkToolkit.emptySchema(), listNotes);
        tk.addPythonTool("read_note",
                "读取指定标题的笔记内容.",
                titleOnlySchema, readNote);
        return tk;
    }

    /** 将 NoteStore 绑定到工具类 (由 AgentRole.addToolkit 内部调用). */
    public static void bindStoreToToolkit(ToolKit toolkit, NoteStore store, AgentRole role) {
        toolkit.bind("store", store);
        toolkit.bind("role", role);
    }
}
