package com.maf.scheduler.tools.toolkits.talk;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.RolePool;
import com.maf.scheduler.tools.Toolkit;

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
        return "角色间通信工具类: 给团队成员发消息/委托任务 (talk), 查看团队成员 (list_roles)";
    }

}
