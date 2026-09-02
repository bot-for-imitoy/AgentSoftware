package com.agent.software.tools.toolkits.memory;

import com.agent.software.computers.Computer;
import com.agent.software.role.AgentRole;

import com.agent.software.store.NoteStore;
import com.agent.software.core.Types;
import com.agent.software.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * summary - summarize today's work. After saving, it is automatically injected into the system prompt
 * the next day, and the role is switched to OFF_DUTY (off duty); at the end of the day the personal
 * computer is shut down automatically.
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
            agentRole.journal("Saved summary for day " + day + " (" + content.length() + " characters)");
            if (agentRole.state != Types.AgentState.OFF_DUTY) {
                agentRole.setState(Types.AgentState.OFF_DUTY);
                logger.info("[{}] summary complete, role switched to OFF_DUTY", agentRole.roleId);
            }
            try {
                Computer comp = agentRole.computerIfCreated();
                if (comp != null && comp.isOn()) {
                    comp.powerOff();
                    logger.info("[{}] end of day, computer powered off automatically", agentRole.roleId);
                }
            } catch (Exception e) {
                logger.warn("[{}] failed to power off the computer automatically", agentRole.roleId);
            }
            return "summary: Day " + day + " summary saved: " + path
                    + ". You are now OFF_DUTY, computer powered off.";
        }
        return "summary: Day " + day + " summary saved: " + path;
    }

    /** Accepts Integer / Number / numeric string. Returns null if it cannot be parsed. */
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
