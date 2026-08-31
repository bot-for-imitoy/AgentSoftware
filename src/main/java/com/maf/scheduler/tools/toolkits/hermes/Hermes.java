package com.maf.scheduler.tools.toolkits.hermes;

import com.maf.scheduler.role.AgentRole;
import com.maf.scheduler.core.Computer;
import com.maf.scheduler.tools.Toolkit;

/**
 * Hermes 工具类 (Hermes Toolkit) — 调用角色电脑 (容器) 上安装的 Hermes Agent:
 * hermes_new_conversation / hermes_send.
 */
public class Hermes extends Toolkit {

    private final Computer computer;

    public Hermes(Computer computer) {
        this.computer = computer;
        addTool(new HermesNewConversation(computer));
        addTool(new HermesSend(computer));
    }

    public Hermes(AgentRole agentRole) {
        this(agentRole.computer());
    }

    @Override
    public String getDescription(){
        return "Hermes 工具类: 在电脑上的 Hermes Agent 中新建对话 / 发送内容并拿回复";
    }

}
