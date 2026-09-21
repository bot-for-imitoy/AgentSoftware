package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.utils.UUIDObjectManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class RolePool extends UUIDObjectManager<Role> {

    private static final Logger logger = LoggerFactory.getLogger(RolePool.class);

    public AgentSystem agentSystem;

    public RolePool(){
    }

}
