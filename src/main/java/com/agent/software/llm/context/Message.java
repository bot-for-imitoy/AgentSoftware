package com.agent.software.llm.context;

import com.agent.software.utils.UUIDObject;

public abstract class Message extends UUIDObject {

    public final String message;
    public boolean remember = true;

    public class UserMessage extends Message{

        public UserMessage(String message){
            super(message);
        }

        public UserMessage(String message, String uuid){
            super(message, uuid);
        }

    }

    public class ToolMessage extends Message{

        public ToolMessage(String message){
            super(message);
        }

        public ToolMessage(String message, String uuid){
            super(message, uuid);
        }

    }

    public class AssistantMessage extends Message{

        public AssistantMessage(String message){
            super(message);
        }

        public AssistantMessage(String message, String uuid){
            super(message, uuid);
        }

    }

    public Message(String message){
        super();
        this.message = message;
    }

    public Message(String message, String uuid){
        super(uuid);
        this.message = message;
    }

}
