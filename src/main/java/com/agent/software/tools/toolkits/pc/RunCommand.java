package com.agent.software.tools.toolkits.pc;


import com.agent.software.computers.Computer;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * run_command - run a command on the personal computer (e.g. ls, cat, python, git, etc.),
 * and return the command output. Useful for viewing files on the computer, running scripts, and checking project status.
 */
public class RunCommand extends Tool {

    private final Computer computer;

    public RunCommand(Computer computer) {
        super();
        this.computer = computer;
    }

    @Override
    public String getToolName() {
        return "run_command";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("command", "The command to run on your computer.");
        return schema;
    }

    @Override
    public String handler(Map<String, Object> args) {
        Object ocmd = args.get("command");
        if (!(ocmd instanceof String)) {
            return ocmd == null
                    ? "run_command: Error: needs a command"
                    : "run_command: Error: command is not a string";
        }
        String cmd = ((String) ocmd).strip();
        if (cmd.isEmpty()) {
            return "run_command: Error: needs a command";
        }
        return this.computer.runCommand(cmd, 60, 2000);
    }
}
