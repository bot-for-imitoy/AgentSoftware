package com.agent.software.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSchemaTest {

    @Test
    void builderEmitsRequiredAndEnumsInOpenAiShape() {
        JsonSchema schema = JsonSchema.builder()
                .required("name", JsonSchema.Property.string("the name"))
                .property("status", JsonSchema.Property.stringEnum("state", "a", "b"))
                .build();

        assertEquals("object", schema.toMap().get("type"));
        assertEquals(List.of("name"), schema.toMap().get("required"));
        assertTrue(schema.toMap().toString().contains("enum"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fromMapRoundTripsTypesRequiredAndEnums() {
        JsonSchema original = JsonSchema.builder()
                .required("name", JsonSchema.Property.string("the name"))
                .property("count", JsonSchema.Property.integer("how many"))
                .property("status", JsonSchema.Property.stringEnum("state", "a", "b"))
                .build();

        JsonSchema parsed = JsonSchema.fromMap(original.toMap());

        assertEquals(List.of("name"), parsed.required());
        assertEquals("string", parsed.properties().get("name").type());
        assertEquals("integer", parsed.properties().get("count").type());
        assertEquals(List.of("a", "b"), parsed.properties().get("status").enumValues());
    }

    @Test
    void emptyOrNullMapBecomesEmptyObjectSchema() {
        assertEquals(0, JsonSchema.fromMap(null).properties().size());
        assertEquals(0, JsonSchema.fromMap(java.util.Map.of()).properties().size());
    }
}
