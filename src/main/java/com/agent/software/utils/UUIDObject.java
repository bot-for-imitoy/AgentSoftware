package com.agent.software.utils;

import java.util.UUID;

public abstract class UUIDObject {

    public final String uuid;

    public UUIDObject(){
        uuid = UUID.randomUUID().toString();
    }

    public UUIDObject(String uuid){
        this.uuid = uuid;
    }

}
