package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/** Minimal JSON Schema builder shared by every tool; unknown properties are rejected. */
public final class AssistantSchemas {

    private AssistantSchemas() {
    }

    public static ObjectNode object() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.putArray("required");
        schema.put("additionalProperties", false);
        return schema;
    }

    public static void text(ObjectNode schema, String name, String description, int maxLength,
                            boolean required) {
        ObjectNode property = properties(schema).putObject(name);
        property.put("type", "string");
        property.put("description", description);
        property.put("maxLength", maxLength);
        required(schema, name, required);
    }

    public static void integer(ObjectNode schema, String name, String description, boolean required) {
        ObjectNode property = properties(schema).putObject(name);
        property.put("type", "integer");
        property.put("description", description);
        required(schema, name, required);
    }

    public static void object(ObjectNode schema, String name, String description, boolean required) {
        ObjectNode property = properties(schema).putObject(name);
        property.put("type", "object");
        property.put("description", description);
        property.put("additionalProperties", true);
        required(schema, name, required);
    }

    private static ObjectNode properties(ObjectNode schema) {
        return (ObjectNode) schema.get("properties");
    }

    private static void required(ObjectNode schema, String name, boolean required) {
        if (required) {
            ((ArrayNode) schema.get("required")).add(name);
        }
    }
}
