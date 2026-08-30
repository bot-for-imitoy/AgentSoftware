package com.maf.scheduler.tools;

import java.util.Map;

public abstract class Tool {

    public Tool(){
    }

    public abstract String getToolName();

    public abstract Map<String, Object> getSchema();

    public abstract String handler(Map<String, Object> args);

}
