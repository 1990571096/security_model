package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;

/**
 * Explicit, small evidence envelope for the model. Business tool responses are never
 * forwarded wholesale: only the fields below are copied, every text is redacted again,
 * and the total payload is capped.
 */
final class AssistantModelEvidence {

    private AssistantModelEvidence() {
    }

    static String forTool(ObjectMapper objectMapper, String toolName, String summary, JsonNode data) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", toolName);
        root.put("summary", KnowledgeSanitizer.forModel(summary, 400));
        if (data == null || !data.isObject()) {
            return root.toString();
        }
        ObjectNode evidence = root.putObject("evidence");
        copyText(objectMapper, data, evidence, "taskId");
        copyText(objectMapper, data, evidence, "taskName");
        copyText(objectMapper, data, evidence, "status");
        copyText(objectMapper, data, evidence, "progress");
        copyText(objectMapper, data, evidence, "datasetId");
        copyText(objectMapper, data, evidence, "algorithmId");
        copyText(objectMapper, data, evidence, "hyperId");
        copyText(objectMapper, data, evidence, "currentAttempt");
        copyText(objectMapper, data, evidence, "startTime");
        copyText(objectMapper, data, evidence, "endTime");
        copyText(objectMapper, data, evidence, "customParams");
        copyText(objectMapper, data, evidence, "errorCode");
        copyText(objectMapper, data, evidence, "failureReason");
        copyText(objectMapper, data, evidence, "selectionKind");
        copyText(objectMapper, data, evidence, "cleanPurityPercentByIsolationRatio");
        copyText(objectMapper, data, evidence, "selectedSampleCountByIsolationRatio");
        copyText(objectMapper, data, evidence, "cleanSubsetExportAvailable");
        copyText(objectMapper, data, evidence, "exportIsolationRatios");
        copyText(objectMapper, data, evidence, "dataset");
        copyCitations(objectMapper, data, evidence);
        return KnowledgeSanitizer.forModel(root.toString(), 2400);
    }

    private static void copyText(ObjectMapper objectMapper, JsonNode source, ObjectNode target, String field) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            target.put(field, KnowledgeSanitizer.forModel(value.asText(), 600));
        } else if (value.isNumber() || value.isBoolean()) {
            target.set(field, value.deepCopy());
        } else if (value.isArray()) {
            ArrayNode safe = target.putArray(field);
            value.forEach(item -> {
                if (item.isNumber() || item.isBoolean()) {
                    safe.add(item.deepCopy());
                } else if (item.isTextual()) {
                    safe.add(KnowledgeSanitizer.forModel(item.asText(), 200));
                }
            });
        } else {
            target.put(field, KnowledgeSanitizer.forModel(value.toString(), 600));
        }
    }

    private static void copyCitations(ObjectMapper objectMapper, JsonNode data, ObjectNode evidence) {
        JsonNode citations = data.get("citations");
        if (citations == null || !citations.isArray()) {
            return;
        }
        ArrayNode safe = evidence.putArray("citations");
        citations.forEach(item -> {
            ObjectNode citation = safe.addObject();
            copyText(objectMapper, item, citation, "citationId");
            copyText(objectMapper, item, citation, "title");
            copyText(objectMapper, item, citation, "sourceType");
            copyText(objectMapper, item, citation, "version");
            copyText(objectMapper, item, citation, "sectionPath");
            copyText(objectMapper, item, citation, "startLine");
            copyText(objectMapper, item, citation, "endLine");
            copyText(objectMapper, item, citation, "snippet");
        });
    }
}
