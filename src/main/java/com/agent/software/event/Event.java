package com.agent.software.event;

import com.agent.software.role.Role;

import java.util.UUID;

public class Event {

    public final Role from, target;
    public final long targetTime;
    public final String content;
    public final String id;

    public class Builder {

        public Role from, target;
        public long targetTime;
        public String content;
        public String id;

        public Builder(){
            id = UUID.randomUUID().toString();
        }

        public void setFromRole(Role role){
            from = role;
        }

        public void setTargetRole(Role role){
            target = role;
        }

        public void setTargetTime(long time){
            targetTime = time;
        }

        public Event build(){
            return new Event(from, target, targetTime, content, id);
        }

    }

    protected Event(Role from, Role target, long targetTime, String content, String id){
        this.from = from;
        this.target = target;
        this.targetTime = targetTime;
        this.content = content;
        this.id = id;
    }

}
