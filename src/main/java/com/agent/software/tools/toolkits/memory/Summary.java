package com.agent.software.tools.toolkits.memory;

import com.agent.software.role.AgentRole;
import com.maf.scheduler.core.Computer;
import com.agent.software.store.NoteStore;
import com.agent.software.core.Types;
import com.agent.software.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * summary — 总结今天的工作. 保存后下一天自动注入系统提示词,
 * 并将角色切换为 OFF_DUTY (下班), 一天结束自动关闭个人电脑.
 */
public class Summary extends Tool {

    private static final Logger logger = LoggerFactory.getLogger(Summary.class);

    private final NoteStore noteStore;
    private final AgentRole agentRole;

    public Summary(NoteStore noteStore, AgentRole agentRole) {
        super();
        this.noteStore = noteStore;
        this.agentRole = agentRole;
    }

    @Override
    public String getToolName() {
        return "summary";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("content", "The summary of today's work. Should include: work done, key decisions, unfinished items.");
        schema.put("day", "(Optional, default to today. ) Day number.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object ocontent = args.get("content");
        if (!(ocontent instanceof String)) {
            return ocontent == null
                    ? "summary: Error: needs summary content"
                    : "summary: Error: content is not a string";
        }
        String content = ((String) ocontent).strip();
        if (content.isEmpty()) {
            return "summary: Error: needs summary content";
        }
        Integer day = toInt(args.get("day"));
        if (day == null && agentRole != null) {
            day = agentRole.timeManager().dayNumber();
        }
        if (day == null) {
            day = 1;
        }
        Path path = this.noteStore.saveSummary(content, day);
        if (agentRole != null) {
            agentRole.journal("保存第 " + day + " 天总结 (" + content.length() + " 字符)");
            if (agentRole.state != Types.AgentState.OFF_DUTY) {
                agentRole.setState(Types.AgentState.OFF_DUTY);
                logger.info("[{}] 总结完成, 角色已切换为 OFF_DUTY", agentRole.roleId);
            }
            try {
                Computer comp = agentRole.computerIfCreated();
                if (comp != null && comp.isOn()) {
                    comp.powerOff();
                    logger.info("[{}] 一天结束, 电脑已自动关机", agentRole.roleId);
                }
            } catch (Exception e) {
                logger.warn("[{}] 电脑自动关机失败", agentRole.roleId);
            }
            return "summary: Day " + day + " summary saved: " + path
                    + ". You are now OFF_DUTY, computer powered off.";
        }
        return "summary: Day " + day + " summary saved: " + path;
    }

    /** 兼容 Integer / Number / 数字字符串. 无法解析返回 null. */
    private static Integer toInt(Object o) {
        if (o instanceof Integer) {
            return (Integer) o;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && s.matches("-?\\d+")) {
            return Integer.parseInt(s);
        }
        return null;
    }
}
