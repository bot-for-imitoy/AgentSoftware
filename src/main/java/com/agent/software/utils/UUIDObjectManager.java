package com.agent.software.utils;

import java.util.ArrayList;
import java.util.List;

public abstract class UUIDObjectManager<T extends UUIDObject> {

    protected final List<T> values;

    public UUIDObjectManager(){
        this.values = new ArrayList<>();
    }

    public void add(T value){
        this.values.add(value);
    }

    public boolean contains(T value){
        return this.values.contains(value);
    }

    public UUIDObject findObjectByUUID(String uuid){
        for(UUIDObject uuidObject : values){
            if(uuidObject.uuid.equals(uuid)){
                return uuidObject;
            }
        }
        return null;
    }

}
