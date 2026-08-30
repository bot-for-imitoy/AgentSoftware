package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.Computer;
import com.maf.scheduler.core.Json;
import com.maf.scheduler.core.NoteStore;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
import com.maf.scheduler.core.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆工具类 (Memory ToolKit) — 兼容保留类.
 *
 * 注意: 笔记工具已从 memory 中分离 (写入 toolkits.note.Note):
 *   write_note / edit_note / list_notes / read_note / delete_note
 * 现在 memory 只包含记忆相关内容: summary (每日总结).
 *
 * 当前默认装配已切换到模板风格 toolkits.* (见 Toolkits.defaultToolkits),
 * 本类仅为旧接口兼容保留.
 */
public final class MemoryToolkit {

    private static final Logger logger = LoggerFactory.getLogger(MemoryToolkit.class);

    private MemoryToolkit() {
    }

    /** 创建记忆工具类 (只含 summary). */
    public static ToolKit createMemoryToolkit() {
        ToolKit tk = new ToolKit("memory", "记忆工具类: 每日总结 (笔记工具见 note 工具类)");

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

        Map<String, Object> summarySchema = new LinkedHashMap<>();
        summarySchema.put("type", "object");
        summarySchema.put("properties", Map.of(
                "content", TalkToolkit.mapOf("string", "今日总结内容"),
                "day", TalkToolkit.mapOf("integer", "第几天 (可选, 默认当前天)")));
        summarySchema.put("required", List.of("content"));

        tk.addPythonTool("summary",
                "总结今天的工作. 调用此工具后, 总结内容会被保存, 并在下一天自动注入到你的系统提示词中. "
                        + "同时你会切换为 OFF_DUTY (下班) 状态. content 应包含: 今日完成的工作, 关键决策, 未完成事项.",
                summarySchema, summary);
        return tk;
    }

    /** 将 NoteStore 绑定到工具类 (由 AgentRole.addToolkit 内部调用). */
    public static void bindStoreToToolkit(ToolKit toolkit, NoteStore store, AgentRole role) {
        toolkit.bind("store", store);
        toolkit.bind("role", role);
    }
}
