package com.agent.software.role;

import com.agent.software.event.Event;
import com.agent.software.utils.UUIDObject;

import java.util.Queue;

public final class Role extends UUIDObject {

    public final static int STATE_IDLE = 0;
    public final static int STATE_BUSY = 1;

    private int state;
    private Queue<Event> events;

    public Role(){
        state = STATE_IDLE;
    }

    public Role(String uuid){
        super(uuid);
        state = STATE_IDLE;
    }

    public int getState(){
        return state;
    }

}
