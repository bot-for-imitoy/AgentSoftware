package com.agent.software.tools.toolkits.time;

import com.agent.software.role.Role;
import com.agent.software.core.Types;
import com.agent.software.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * take_rest — rest. Calling it immediately enters the rest state (ON_DUTY_IDLE);
 * you are automatically woken up when tasks or events (scheduled reminders/other people's messages/going to work) arrive.
 */
public class TakeRest extends Tool {

    private static final Logger logger = LoggerFactory.getLogger(TakeRest.class);

    private final Role role;

    public TakeRest(Role role) {
        super();
        this.role = role;
    }

    @Override
    public String getToolName() {
        return "take_rest";
    }

    @Override
    public Map<String, Object> getSchema() {
        return new LinkedHashMap<>();
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role != null && role.state != Types.AgentState.ON_DUTY_IDLE) {
            role.setState(Types.AgentState.ON_DUTY_IDLE);
            logger.info("[{}] rest started (state ON_DUTY_IDLE, waiting for events to wake up)", role.roleId);
        }
        return "take_rest: rest started (state ON_DUTY_IDLE). You will be automatically woken up when tasks or events arrive.";
    }
}
