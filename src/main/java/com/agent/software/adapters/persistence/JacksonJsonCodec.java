package com.agent.software.adapters.persistence;

import com.agent.software.ports.JsonCodec;

import java.util.Map;

/**
 * 基于 Jackson 的 JSON 编解码器：全系统唯一出现 Map&lt;String,Object&gt; 的边界之一。
 */
public final class JacksonJsonCodec implements JsonCodec {

    @Override
    public String write(Object value) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public Map<String, Object> readMap(String json) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public <T> T read(String json, Class<T> type) {
        throw new UnsupportedOperationException("skeleton");
    }
}
