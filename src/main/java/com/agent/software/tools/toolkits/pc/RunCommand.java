package com.agent.software.tools.toolkits.pc;

import com.agent.software.role.Role;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** run_command：在自己电脑上执行命令。 */
public class RunCommand extends Tool {

    private final Role role;

    public RunCommand(Role role) {
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "run_command";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("command", "shell command to run on your own computer");
        schema.put("timeout", Map.of("type", "integer", "description", "timeout seconds (default 60)"));
        return schema;
    }

    @Override
    public String getDescription() {
        return "Run a shell command on your own personal computer.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || !role.hasComputer()) {
            return "run_command error: no computer";
        }
        String command = str(args.get("command"));
        if (command.isBlank()) {
            return "run_command error: empty command";
        }
        int timeout = intArg(args.get("timeout"), 60);
        return role.getComputer().runCommand(command, timeout, 20_000);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intArg(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
