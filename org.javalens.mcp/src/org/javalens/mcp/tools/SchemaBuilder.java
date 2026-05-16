package org.javalens.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fluent builder for MCP tool input schemas (JSON Schema fragments). Replaces
 * the ~10-line per-tool {@code LinkedHashMap}/{@code Map.of} boilerplate with a
 * single expression.
 *
 * <p>Typical usage:
 * <pre>{@code
 * return SchemaBuilder.object()
 *     .required("typeName", "string", "Fully qualified type name")
 *     .optional("maxResults", "integer", "Max results (default 100)")
 *     .build();
 * }</pre>
 *
 * <p>For schemas with enum constraints, use {@link #requiredEnum} /
 * {@link #optionalEnum}. For shapes too complex for the fluent shortcuts
 * (arrays of nested objects, etc.), use {@link #requiredCustom} /
 * {@link #optionalCustom} with a raw schema fragment.
 *
 * <p>{@link #build()} returns a fresh {@link LinkedHashMap} the caller may
 * mutate freely; the builder is single-use.
 */
public final class SchemaBuilder {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private SchemaBuilder() {}

    public static SchemaBuilder object() {
        return new SchemaBuilder();
    }

    public SchemaBuilder required(String name, String type, String description) {
        properties.put(name, propertySchema(type, description));
        required.add(name);
        return this;
    }

    public SchemaBuilder optional(String name, String type, String description) {
        properties.put(name, propertySchema(type, description));
        return this;
    }

    public SchemaBuilder requiredEnum(String name, String description, List<String> values) {
        properties.put(name, enumSchema(description, values));
        required.add(name);
        return this;
    }

    public SchemaBuilder optionalEnum(String name, String description, List<String> values) {
        properties.put(name, enumSchema(description, values));
        return this;
    }

    /** Escape hatch for shapes too complex for the fluent shortcuts. */
    public SchemaBuilder requiredCustom(String name, Map<String, Object> schema) {
        properties.put(name, schema);
        required.add(name);
        return this;
    }

    /** Escape hatch for shapes too complex for the fluent shortcuts. */
    public SchemaBuilder optionalCustom(String name, Map<String, Object> schema) {
        properties.put(name, schema);
        return this;
    }

    public Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", List.copyOf(required));
        }
        return schema;
    }

    private static Map<String, Object> propertySchema(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> enumSchema(String description, List<String> values) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", List.copyOf(values));
        return p;
    }
}
