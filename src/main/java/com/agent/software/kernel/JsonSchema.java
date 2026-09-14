package com.agent.software.kernel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed JSON-Schema-style description of a tool's arguments.
 *
 * <p>Replaces the historical flat {@code Map<String,Object>} schemas where every
 * parameter was an optional string and {@code required} was never emitted.
 * Instances are immutable and preserve declaration order.
 */
public record JsonSchema(String type, Map<String, Property> properties, List<String> required) {

    /** One property of an object schema. */
    public record Property(String type, String description, List<String> enumValues) {

        public Property {
            Objects.requireNonNull(type, "type");
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }

        public static Property string(String description) {
            return new Property("string", description, List.of());
        }

        public static Property integer(String description) {
            return new Property("integer", description, List.of());
        }

        public static Property bool(String description) {
            return new Property("boolean", description, List.of());
        }

        public static Property stringEnum(String description, String... values) {
            return new Property("string", description, List.of(values));
        }
    }

    public JsonSchema {
        Objects.requireNonNull(type, "type");
        Map<String, Property> props = properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        properties = Collections.unmodifiableMap(props);
        required = required == null ? List.of() : List.copyOf(required);
    }

    /** An object schema with no properties. */
    public static JsonSchema object() {
        return new JsonSchema("object", Map.of(), List.of());
    }

    /** Fluent builder; preserves insertion order. */
    public static Builder builder() {
        return new Builder();
    }

    /** Render to the OpenAI function-calling {@code parameters} shape. */
    public Map<String, Object> toMap() {
        Map<String, Object> props = new LinkedHashMap<>();
        properties.forEach((name, p) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", p.type());
            if (p.description() != null && !p.description().isEmpty()) {
                m.put("description", p.description());
            }
            if (!p.enumValues().isEmpty()) {
                m.put("enum", new ArrayList<>(p.enumValues()));
            }
            props.put(name, m);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("properties", props);
        if (!required.isEmpty()) {
            out.put("required", new ArrayList<>(required));
        }
        return out;
    }

    /** Mutable builder for {@link JsonSchema}. */
    public static final class Builder {
        private final Map<String, Property> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public Builder property(String name, Property property) {
            properties.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(property, "property"));
            return this;
        }

        public Builder required(String name, Property property) {
            property(name, property);
            required.add(name);
            return this;
        }

        public JsonSchema build() {
            return new JsonSchema("object", properties, required);
        }
    }
}
