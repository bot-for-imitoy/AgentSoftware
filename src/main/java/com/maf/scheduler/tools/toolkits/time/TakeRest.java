package com.maf.scheduler.tools.toolkits.time;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.Types;
import com.maf.scheduler.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * take_rest — 休息. 调用后立即进入休息状态 (ON_DUTY_IDLE);
 * 有任务或事件 (定时提醒/他人消息/上班) 到来时自动唤醒.
 */
public class TakeRest extends Tool {

    private static final Logger logger = LoggerFactory.getLogger(TakeRest.class);

    private final AgentRole agentRole;

    public TakeRest(AgentRole agentRole) {
        super();
        this.agentRole = agentRole;
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
        if (agentRole != null && agentRole.state != Types.AgentState.ON_DUTY_IDLE) {
            agentRole.setState(Types.AgentState.ON_DUTY_IDLE);
            logger.info("[{}] 开始休息 (状态 ON_DUTY_IDLE, 等待事件唤醒)", agentRole.roleId);
        }
        return "take_rest: 已开始休息 (状态 ON_DUTY_IDLE). 有任务或事件到来时会自动唤醒.";
    }
}
