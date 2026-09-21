package com.agent.software.llm.context;

import com.agent.software.utils.UUIDObjectManager;

import java.util.ArrayList;
import java.util.List;

public final class Context extends UUIDObjectManager<Message> {

    public Context(){
        super();
    }

    public List<Message> find(String pat){
        List<Message> result = new ArrayList<>();
        for(Message message : this.values){
            if(message.message.contains(pat)){
                result.add(message);
            }
        }
        return result;
    }

    public void forget(Message message){
        if(this.values.contains(message)){
            message.remember = false;
        }else {
            throw new RuntimeException("Message not found");
        }
    }

}
