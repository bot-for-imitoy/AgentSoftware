package com.agent.software.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Immutable, null-tolerant bag of event/task attributes.
 *
 * <p>Replaces the raw {@code Map<String,Object>} that used to cross module
 * boundaries: producers and consumers share one typed accessor instead of
 * re-implementing casts everywhere.
 */
public record Payload(Map<String, Object> values) {

    public Payload {
        Map<String, Object> copy = values == null ? new LinkedHashMap<>() : new LinkedHashMap<>(values);
        values = Collections.unmodifiableMap(copy);
    }

    public static Payload empty() {
        return new Payload(Map.of());
    }

    public static Payload of(Map<String, Object> values) {
        return new Payload(values);
    }

    public static Payload of(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return new Payload(m);
    }

    /** Raw view; mutations are not reflected. */
    public Map<String, Object> asMap() {
        return values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Optional<Object> find(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public String str(String key, String def) {
        Object v = values.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public Optional<String> str(String key) {
        Object v = values.get(key);
        return v == null ? Optional.empty() : Optional.of(String.valueOf(v));
    }

    public int intVal(String key, int def) {
        Object v = values.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.strip());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public OptionalInt intVal(String key) {
        Object v = values.get(key);
        if (v instanceof Number n) {
            return OptionalInt.of(n.intValue());
        }
        if (v instanceof String s) {
            try {
                return OptionalInt.of(Integer.parseInt(s.strip()));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    public double doubleVal(String key, double def) {
        Object v = values.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.strip());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    public boolean boolVal(String key, boolean def) {
        Object v = values.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return switch (s.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "1", "true", "yes", "on" -> true;
                case "0", "false", "no", "off" -> false;
                default -> def;
            };
        }
        return def;
    }

    /** Return a new payload with one extra entry. */
    public Payload with(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>(values);
        m.put(key, value);
        return new Payload(m);
    }

    /** Merge another payload on top of this one. */
    public Payload mergedWith(Payload other) {
        Map<String, Object> m = new LinkedHashMap<>(values);
        if (other != null) {
            m.putAll(other.values);
        }
        return new Payload(m);
    }
}
