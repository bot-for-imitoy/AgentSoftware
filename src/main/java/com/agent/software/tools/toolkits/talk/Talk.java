package com.agent.software.tools.toolkits.talk;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import com.agent.software.tools.Toolkit;

/**
 * 通信工具类 (Talk Toolkit) — 角色间消息传递与任务委托:
 * talk / list_roles.
 *
 * 注意: talk 仅限同组成员之间交流 (跨组请用邮件 send_email).
 */
public class Talk extends Toolkit {

    private final AgentRole agentRole;
    private final RolePool pool;

    public Talk(AgentRole agentRole, RolePool pool) {
        this.agentRole = agentRole;
        this.pool = pool;
        addTool(new TalkTo(agentRole, pool));
        addTool(new ListRoles(pool));
    }

    @Override
    public String getDescription(){
        return "Role communication toolkit: send messages / delegate tasks to team members (talk), view team members (list_roles)";
    }

}
