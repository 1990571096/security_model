package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

import java.util.Iterator;
import java.util.Set;

/** Strict, small argument parser; unknown keys are rejected rather than ignored. */
public final class AssistantArgs {

    private AssistantArgs() {
    }

    public static void rejectUnknown(JsonNode arguments, Set<String> allowed) {
        requireObject(arguments);
        Iterator<String> names = arguments.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw invalid("不支持的参数: " + name);
            }
        }
    }

    public static JsonNode requireObject(JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw invalid("参数必须是 JSON 对象");
        }
        return arguments;
    }

    public static String requireText(JsonNode arguments, String field, int maxLength) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid("缺少必填参数: " + field);
        }
        String text = value.asText().trim();
        if (text.length() > maxLength) {
            throw invalid("参数过长: " + field);
        }
        return text;
    }

    public static String optionalText(JsonNode arguments, String field, int maxLength) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid("参数类型错误: " + field);
        }
        String text = value.asText().trim();
        if (text.length() > maxLength) {
            throw invalid("参数过长: " + field);
        }
        return text;
    }

    public static Long requireLong(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isIntegralNumber()) {
            throw invalid("缺少整数参数: " + field);
        }
        return value.asLong();
    }

    public static Long optionalLong(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw invalid("参数类型错误: " + field);
        }
        return value.asLong();
    }

    public static ObjectNode requireObjectArgument(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        if (value == null || !value.isObject()) {
            throw invalid("缺少对象参数: " + field);
        }
        return (ObjectNode) value;
    }

    public static String optionalObjectText(JsonNode arguments, String field, int maxLength) {
        JsonNode value = arguments.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw invalid("参数必须是 JSON 对象: " + field);
        }
        String text = value.toString();
        if (text.length() > maxLength) {
            throw invalid("参数对象过大: " + field);
        }
        return text;
    }

    private static AssistantException invalid(String message) {
        return new AssistantException("ASSISTANT_TOOL_ARGUMENTS_INVALID", HttpStatus.BAD_REQUEST, message);
    }
}
