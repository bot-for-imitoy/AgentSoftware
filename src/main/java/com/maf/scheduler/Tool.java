package com.maf.scheduler;

import java.util.Map;

public interface Tool {

    public String handler(Map<String, Object> args);

    public void bind(String key, Object object);

}
