package com.agent.software.event;

import com.agent.software.AgentSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class EventBus {

    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    public List<Event> events;
    public AgentSystem agentSystem;

    public EventBus(){
        // TODO
    }



}
