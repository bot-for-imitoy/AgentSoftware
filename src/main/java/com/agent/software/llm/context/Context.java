package com.agent.software.llm.context;

import com.agent.software.utils.Data;
import com.agent.software.utils.DataRegistry;
import com.agent.software.utils.Json;
import com.agent.software.utils.UUIDObjectManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个角色的对话上下文：消息集合。
 *
 * <p>一天结束时 {@link #forgetAll()} 把当前消息全部标记 {@code remember=false}：
 * 它们仍留在内存里（可追溯），但不再进 prompt。这样就不需要"每日总结"。
 */
public final class Context extends UUIDObjectManager<Message> implements Data {

    static {
        // 触发三个消息子类的静态注册，保证跨进程恢复时 DataRegistry 能 new 出具体类型
        String[] types = {UserMessage.DATA_TYPE, AssistantMessage.DATA_TYPE, ToolMessage.DATA_TYPE};
        if (types.length == 0) {
            throw new IllegalStateException("unreachable");
        }
    }

    private int day = 1;

    public Context() {
        super();
    }

    /** 只返回 remember=true 的消息（喂给 LLM 的那部分）。 */
    public List<Message> messages() {
        List<Message> out = new ArrayList<>();
        for (Message m : all()) {
            if (m.remember) {
                out.add(m);
            }
        }
        return out;
    }

    /** 全量消息（含 remember=false）。 */
    public List<Message> history() {
        return all();
    }

    public List<Message> find(String pat) {
        List<Message> out = new ArrayList<>();
        if (pat == null) {
            return out;
        }
        for (Message m : all()) {
            if (m.content != null && m.content.contains(pat)) {
                out.add(m);
            }
        }
        return out;
    }

    public Message last() {
        List<Message> all = all();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    /** 粗略 token 估算：字符数 / 3。 */
    public long estimatedTokens() {
        long chars = 0;
        for (Message m : messages()) {
            chars += m.content == null ? 0 : m.content.length();
        }
        return chars / 3;
    }

    public void forget(Message m) {
        if (m == null || !contains(m)) {
            throw new IllegalArgumentException("message not in context");
        }
        m.remember = false;
    }

    /** 把当前所有消息移出 prompt（下班/换天时调用）。 */
    public void forgetAll() {
        for (Message m : all()) {
            m.remember = false;
        }
    }

    @Override
    public void clear() {
        super.clear();
    }

    public int getDay() {
        return day;
    }

    /** 结束一天：日期前进（幂等——同一天重复结束不会多跳）。 */
    public void endDay(int day) {
        if (day >= this.day) {
            this.day = day + 1;
        }
    }

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("day", Integer.toString(day));
        List<Map<String, String>> list = new ArrayList<>();
        for (Message m : all()) {
            list.add(m.getData());
        }
        d.put("messages", Json.stringify(list));
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        clear();
        if (data == null) {
            return;
        }
        this.day = parseInt(data.get("day"), 1);
        String raw = data.get("messages");
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (Object o : Json.parseArray(raw)) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, String> record = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                record.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            }
            String type = record.getOrDefault("data_type", UserMessage.DATA_TYPE);
            if (!DataRegistry.supports(type)) {
                continue;
            }
            Data created = DataRegistry.create(type);
            created.loadData(record);
            if (created instanceof Message msg) {
                add(msg);
            }
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
