package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Provider-neutral assistant model port. It receives only the question, sanitized
 * observations and the allow-listed tool schemas. It cannot call a tool directly: the
 * orchestrator executes the returned name through the registry and enforces limits.
 */
public interface AssistantModel {

    ModelReply respond(ModelRequest request);

    record ModelRequest(String question, List<ModelMessage> messages,
                        List<AssistantToolDescriptor> tools, int remainingToolCalls) {
    }

    record ModelMessage(String role, String content) {
    }

    record ToolCall(String toolName, JsonNode arguments) {
    }

    record ModelReply(String status, String answer, ToolCall toolCall, String model, String message) {
        public static ModelReply answer(String answer, String model) {
            return new ModelReply("ok", answer, null, model, null);
        }

        public static ModelReply toolCall(String name, JsonNode arguments, String model) {
            return new ModelReply("ok", null, new ToolCall(name, arguments), model, null);
        }

        public static ModelReply unavailable(String status, String message) {
            return new ModelReply(status, null, null, null, message);
        }
    }
}
