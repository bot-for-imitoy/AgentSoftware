package com.agent.software.tools.toolkits.client;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** talk_to_client：和甲方口头沟通，可选择等待回复。 */
public class TalkToClient extends Tool {

    private final Role role;

    public TalkToClient(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "talk_to_client";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("message", "what to say to the client");
        schema.put("wait", Map.of("type", "boolean", "description", "wait for the client's reply (default true)"));
        return schema;
    }

    @Override
    public String getDescription() {
        return "Talk to the client (a real person). Only one role can talk to the client at a time.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null) {
            return "talk_to_client error: no role";
        }
        String message = args.get("message") == null ? "" : String.valueOf(args.get("message"));
        boolean wait = !(args.get("wait") instanceof Boolean b) || b;
        return role.talkToClient(message, wait);
    }
}
