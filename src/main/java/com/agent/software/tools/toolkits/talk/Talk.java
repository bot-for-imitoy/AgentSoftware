package com.agent.software.tools.toolkits.talk;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** 同事沟通工具包：talk / list_roles。 */
public class Talk extends Toolkit {

    private final Role role;

    public Talk(Role role) {
        this.role = role;
        addTool(new TalkTo(role));
        addTool(new ListRoles(role));
    }

    @Override
    public String getDescription() {
        return "Talk to colleagues: talk (optionally wait for a reply) / list_roles";
    }
}
