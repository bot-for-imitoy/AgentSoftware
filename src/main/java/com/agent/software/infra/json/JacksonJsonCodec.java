package com.agent.software.infra.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.agent.software.kernel.DomainError;
import com.agent.software.kernel.Payload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Jackson 的 JSON 编解码器：全系统唯一出现 Map&lt;String,Object&gt; 的边界之一。
 *
 * <p>除了默认的 record / Map 支持，这里还注册了三个自定义编解码：
 * <ul>
 *   <li>{@link Payload}：kernel 刻意不认识 Jackson，所以它的磁盘形状由这里定义；</li>
 *   <li>{@link java.time.LocalDate} / {@link java.time.Instant}：写成 ISO-8601 字符串，
 *       避免为了时间类型再引入 {@code jackson-datatype-jsr310} 依赖。</li>
 * </ul>
 */
public final class JacksonJsonCodec implements JsonCodec {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(payloadAndTimeModule())
            .build();

    private static com.fasterxml.jackson.databind.Module payloadAndTimeModule() {
        com.fasterxml.jackson.databind.module.SimpleModule module =
                new com.fasterxml.jackson.databind.module.SimpleModule("agent-software");

        module.addSerializer(Payload.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override
            public void serialize(Payload value, com.fasterxml.jackson.core.JsonGenerator gen,
                                  com.fasterxml.jackson.databind.SerializerProvider provider)
                    throws java.io.IOException {
                gen.writeObject(value == null ? java.util.Map.of() : value.asMap());
            }
        });
        module.addDeserializer(Payload.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            @SuppressWarnings("unchecked")
            public Payload deserialize(com.fasterxml.jackson.core.JsonParser p,
                                       com.fasterxml.jackson.databind.DeserializationContext ctx)
                    throws java.io.IOException {
                Map<String, Object> map = p.readValueAs(Map.class);
                return Payload.ofMap(map == null ? new LinkedHashMap<>() : map);
            }
        });

        module.addSerializer(java.time.LocalDate.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override
            public void serialize(java.time.LocalDate value, com.fasterxml.jackson.core.JsonGenerator gen,
                                  com.fasterxml.jackson.databind.SerializerProvider provider)
                    throws java.io.IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(java.time.LocalDate.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public java.time.LocalDate deserialize(com.fasterxml.jackson.core.JsonParser p,
                                                   com.fasterxml.jackson.databind.DeserializationContext ctx)
                    throws java.io.IOException {
                return java.time.LocalDate.parse(p.getValueAsString());
            }
        });

        module.addSerializer(java.time.Instant.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
            @Override
            public void serialize(java.time.Instant value, com.fasterxml.jackson.core.JsonGenerator gen,
                                  com.fasterxml.jackson.databind.SerializerProvider provider)
                    throws java.io.IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(java.time.Instant.class, new com.fasterxml.jackson.databind.JsonDeserializer<>() {
            @Override
            public java.time.Instant deserialize(com.fasterxml.jackson.core.JsonParser p,
                                                 com.fasterxml.jackson.databind.DeserializationContext ctx)
                    throws java.io.IOException {
                return java.time.Instant.parse(p.getValueAsString());
            }
        });
        return module;
    }

    @Override
    public String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new DomainError("json.write.failed", "JSON 序列化失败", e);
        }
    }

    /** 缩进写出（落盘可读性）。 */
    public String writePretty(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new DomainError("json.write.failed", "JSON 序列化失败", e);
        }
    }

    @Override
    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            throw new DomainError("json.parse.failed", "JSON 解析失败", e);
        }
    }

    @Override
    public <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new DomainError("json.parse.failed", "JSON 解析失败: " + type.getSimpleName(), e);
        }
    }

    /** 平滑编解码（读档容错用）：失败返回空 Map。 */
    public Map<String, Object> tryReadMap(String json) {
        try {
            return readMap(json);
        } catch (DomainError e) {
            return new LinkedHashMap<>();
        }
    }
}
