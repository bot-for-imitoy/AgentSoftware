package com.agent.software.infra.json;

import java.util.Map;

/**
 * JSON 编解码能力。
 *
 * <p>放在 ports 是为了让 model/policy/engine 不依赖 Jackson；只有适配器实现它，
 * {@code Map<String,Object>} 也只允许在这里出现。
 */
public interface JsonCodec {

    String write(Object value);

    Map<String, Object> readMap(String json);

    <T> T read(String json, Class<T> type);
}
