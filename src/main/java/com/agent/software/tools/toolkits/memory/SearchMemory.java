package com.agent.software.tools.toolkits.memory;

import com.agent.software.llm.context.Context;
import com.agent.software.llm.context.Message;
import com.agent.software.llm.context.SemanticMemory;
import com.agent.software.llm.context.ToolMessage;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;
import com.agent.software.utils.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * search_memory：按语义找"我以前的记忆"里和 query 最接近的内容。
 *
 * <p>搜的是**全部**历史消息，包括已经 {@code remember=false}（因为条数超阈值被移出 prompt）
 * 的那些 —— 这正是这个工具存在的意义：淘汰不等于遗忘。
 */
public class SearchMemory extends Tool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;
    private static final int SNIPPET = 400;

    private final Role role;

    public SearchMemory(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "search_memory";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("query", "What you are trying to recall — a topic, a decision, a file path, a name.");
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "(Optional) how many memories to return, default " + DEFAULT_LIMIT
                + ", max " + MAX_LIMIT + ".");
        schema.put("limit", limit);
        return schema;
    }

    @Override
    public String getDescription() {
        return "Semantically search your own past conversation memory (everything you read, said or did). "
                + "It also covers messages that were dropped from the recent prompt to save context, so use "
                + "it when you need to recall something from earlier in the project.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null) {
            return "search_memory error: no role";
        }
        if (role.getSystem() == null) {
            return "search_memory error: role not bound to a system";
        }
        Context context = role.getContext();
        SemanticMemory memory = context.memory();
        if (memory == null || !memory.enabled()) {
            return "search_memory error: semantic memory is not enabled (no embedding model configured; "
                    + "set embedding.model, and embedding.api_key/base_url fall back to llm.*)";
        }
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query")).strip();
        if (query.isEmpty()) {
            return "search_memory error: needs a query";
        }
        int limit = DEFAULT_LIMIT;
        Object rawLimit = args.get("limit");
        if (rawLimit != null) {
            Integer parsed = toInt(rawLimit);
            if (parsed == null) {
                return "search_memory error: limit is not an integer";
            }
            limit = Math.max(1, Math.min(MAX_LIMIT, parsed));
        }
        List<SemanticMemory.Hit> hits = memory.search(context, query, limit);
        if (hits.isEmpty()) {
            int indexed = 0;
            for (Message m : context.history()) {
                if (m.embedding != null && m.embedding.length > 0) {
                    indexed++;
                }
            }
            return "search_memory: nothing matched \"" + query + "\" (" + indexed
                    + " of " + context.history().size() + " messages are indexed)";
        }
        List<Message> history = context.history();
        StringBuilder sb = new StringBuilder("search_memory: top " + hits.size() + " of "
                + history.size() + " memories for \"" + query + "\"\n");
        int n = 0;
        for (SemanticMemory.Hit hit : hits) {
            Message m = hit.message();
            n++;
            sb.append(n).append(". [").append(m.remember ? "in-prompt" : "forgotten")
                    .append("] ").append(label(m))
                    .append(" | #").append(history.indexOf(m) + 1)
                    .append(" | ").append(when(m))
                    .append(" | similarity ").append(String.format("%.3f", hit.similarity()))
                    .append("\n   ").append(Text.truncate(oneLine(m.content), SNIPPET)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private static String label(Message m) {
        if (m instanceof ToolMessage t && t.name != null && !t.name.isBlank()) {
            return "tool:" + t.name;
        }
        return m.getRole();
    }

    private static String when(Message m) {
        try {
            return java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(m.timestamp),
                    ZoneId.systemDefault()).withNano(0).toString().replace('T', ' ');
        } catch (Exception e) {
            return "?";
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static Integer toInt(Object o) {
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
}
