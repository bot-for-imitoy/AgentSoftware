package com.agent.software.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jackson 帮助类（原 master 的 utils/Json 被删除后恢复）。
 *
 * <p>配置、状态、邮件、LLM 请求响应都走这里，避免每个类各自 new ObjectMapper。
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private Json() {
    }

    public static Map<String, Object> parseObject(String s) {
        if (s == null || s.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(s, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("invalid JSON object", e);
        }
    }

    public static List<Object> parseArray(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(s, new TypeReference<List<Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("invalid JSON array", e);
        }
    }

    public static String stringify(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialize to JSON", e);
        }
    }

    public static String stringifyPretty(Object o) {
        if (o == null) {
            return "null";
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialize to JSON", e);
        }
    }

    public static Map<String, Object> readFile(Path p) {
        try {
            if (!Files.exists(p)) {
                return new LinkedHashMap<>();
            }
            return parseObject(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read JSON file: " + p, e);
        }
    }

    public static void writeFile(Path p, Object o) {
        try {
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, stringifyPretty(o), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write JSON file: " + p, e);
        }
    }
}
