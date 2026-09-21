package com.agent.software.llm;

public abstract class LLM {

    public LLM(){

    }

    public abstract void appendMessage(String message);

    public abstract String request();

}
