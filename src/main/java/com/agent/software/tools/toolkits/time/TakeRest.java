package com.agent.software.tools.toolkits.time;

import com.agent.software.role.Role;
import com.agent.software.role.RoleState;
import com.agent.software.tools.Tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** take_rest：进入空闲，等事件唤醒（没有倒计时，也没有 OFF_DUTY 状态）。 */
public class TakeRest extends Tool {

    private final Role role;

    public TakeRest(Role role) {
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
    public String getDescription() {
        return "Take a rest: stay idle and let events wake you up.";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null) {
            return "take_rest error: no role";
        }
        if (role.getState() != RoleState.IDLE) {
            role.setState(RoleState.IDLE);
        }
        role.journal("take_rest");
        return "take_rest: idle, waiting for events";
    }
}
