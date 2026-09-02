package com.agent.software.tools.toolkits.talk;

import com.agent.software.role.AgentRole;
import com.agent.software.role.RolePool;
import com.agent.software.tools.Toolkit;

/**
 * Communication toolkit (Talk Toolkit) — message passing and task delegation between roles:
 * talk / list_roles.
 *
 * Note: talk is only for communication between members of the same group (for cross-group communication use email send_email).
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
