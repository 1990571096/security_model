package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps database identifiers lossless at the HTTP boundary.
 *
 * <p>JavaScript numbers cannot represent every 64-bit integer exactly. Identifier fields
 * ending in {@code Id} are therefore emitted as decimal strings, while ordinary numeric
 * fields (counts, progress, durations) stay numbers. Requests still accept either a JSON
 * number or a decimal string, so existing numeric callers keep working.</p>
 */
public final class IdJsonSupport {

    private static final int MAX_DEPTH = 12;

    private IdJsonSupport() {
    }

    public static boolean isIdField(String name) {
        return name != null && (name.equals("id") || name.endsWith("Id"));
    }

    /** Recursively copies maps/lists/JSON trees and stringifies identifier values. */
    public static Object normalize(Object value) {
        return normalize(value, 0);
    }

    private static Object normalize(Object value, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return value;
        }
        if (value instanceof JsonNode node) {
            return normalizeJson(node, depth);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object item = entry.getValue();
                if (key instanceof String name && isIdField(name) && item instanceof Number number) {
                    copy.put(key, String.valueOf(number.longValue()));
                } else {
                    copy.put(key, normalize(item, depth + 1));
                }
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(normalize(item, depth + 1));
            }
            return copy;
        }
        return value;
    }

    private static JsonNode normalizeJson(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                JsonNode item = entry.getValue();
                if (isIdField(name) && item != null && item.isIntegralNumber()) {
                    copy.put(name, item.asText());
                } else {
                    copy.set(name, normalizeJson(item, depth + 1));
                }
            });
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> copy.add(normalizeJson(item, depth + 1)));
            return copy;
        }
        return node;
    }
}
