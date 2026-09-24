package com.agent.software.tools.toolkits.talk;

import com.agent.software.event.Priority;
import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * talk：给同事发消息。
 *
 * <p>wait=true 时在 {@code Role.waitForReply} 上阻塞；如果对方正好在等我（我把回复交接过去），
 * 就不再等待。
 */
public class TalkTo extends Tool {

    private static final long DEFAULT_WAIT_MILLIS = 300_000L;

    private final Role role;

    public TalkTo(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "talk";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("target", "target role_id");
        schema.put("message", "message text");
        schema.put("urgency", Map.of("type", "string",
                "enum", java.util.List.of("LOW", "NORMAL", "HIGH", "EMERGENCY"),
                "description", "priority (default NORMAL)"));
        schema.put("wait", Map.of("type", "boolean", "description", "wait for the reply (default false)"));
        return schema;
    }

    @Override
    public String getDescription() {
        return "Send a message to a colleague; optional wait=true blocks until they reply.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null) {
            return "talk error: no role";
        }
        String target = args.get("target") == null ? "" : String.valueOf(args.get("target"));
        String message = args.get("message") == null ? "" : String.valueOf(args.get("message"));
        Priority urgency = Priority.from(args.get("urgency") == null ? "NORMAL" : String.valueOf(args.get("urgency")));
        boolean wait = args.get("wait") instanceof Boolean b && b;
        if (target.isBlank()) {
            return "talk error: no target";
        }
        String result = role.talkTo(target, message, urgency);
        if (!result.startsWith("talk:")) {
            return result;   // 发送失败（解析/权限/没有系统）：不加下班提醒，免得掩盖真正的错因
        }
        if (wait && !result.startsWith("talk: replied")) {
            String reply = role.waitForReply(target, DEFAULT_WAIT_MILLIS);
            return result + "\n" + reply + offDutyNotice();
        }
        return result + offDutyNotice();
    }

    /**
     * 下班时段发出去的 talk 会被 EventBus 暂存到次日上班，对方当时根本收不到 ——
     * 在结果里再提醒一次"该收工了"。
     */
    private String offDutyNotice() {
        if (role == null || role.getSystem() == null
                || role.getSystem().getTimeBus().isWorkingHours()) {
            return "";
        }
        return "\n\n[off duty] It is past shift end, so the recipient is off duty and will NOT see this "
                + "message until the next shift start. Watch the clock and finish your wrap-up "
                + "(tidy up, plan tomorrow, daily summary, take_rest) as soon as you can.";
    }
}
