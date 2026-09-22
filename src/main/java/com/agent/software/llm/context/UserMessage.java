package com.agent.software.llm.context;

import com.agent.software.utils.DataRegistry;

/** 用户/工具结果之外的用户输入；也用于 system。 */
public final class UserMessage extends Message {

    public static final String DATA_TYPE = "user_message";

    static {
        DataRegistry.register(DATA_TYPE, () -> new UserMessage(""));
    }

    public UserMessage(String content) {
        super(content);
    }

    public UserMessage(String uuid, String content) {
        super(uuid, content);
    }

    @Override
    public String getRole() {
        return "user";
    }

    @Override
    public String type() {
        return DATA_TYPE;
    }
}
