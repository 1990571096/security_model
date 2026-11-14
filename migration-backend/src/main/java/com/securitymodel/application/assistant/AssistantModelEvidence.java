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

    private static final int MAX_EVIDENCE_CHARS = 3_200;

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
        copyText(objectMapper, data, evidence, "errorCode");
        copyText(objectMapper, data, evidence, "failureSummary");
        copyText(objectMapper, data, evidence, "selectionKind");
        copyText(objectMapper, data, evidence, "cleanPurityPercentByIsolationRatio");
        copyText(objectMapper, data, evidence, "selectedSampleCountByIsolationRatio");
        copyText(objectMapper, data, evidence, "cleanSubsetExportAvailable");
        copyText(objectMapper, data, evidence, "exportIsolationRatios");
        copyText(objectMapper, data, evidence, "dataset");
        copyCitations(objectMapper, data, evidence);
        if ("get_parameter_guide".equals(toolName)) {
            copyParameterGuide(objectMapper, data, evidence);
        }
        // Never substring a serialized JSON envelope: that produces invalid JSON and can
        // cut a citation in half. If the compact envelope is still too large, return a
        // valid minimal object instead.
        String json = root.toString();
        if (json.length() > MAX_EVIDENCE_CHARS) {
            ObjectNode compact = objectMapper.createObjectNode();
            compact.put("tool", toolName);
            compact.put("summary", KnowledgeSanitizer.forModel(summary, 400));
            compact.put("truncated", true);
            return compact.toString();
        }
        return json;
    }

    private static void copyParameterGuide(ObjectMapper objectMapper, JsonNode data, ObjectNode evidence) {
        JsonNode algorithms = data.get("algorithms");
        if (algorithms != null && algorithms.isArray()) {
            ArrayNode safe = evidence.putArray("algorithms");
            int count = 0;
            for (JsonNode item : algorithms) {
                if (count++ >= 3) {
                    break;
                }
                ObjectNode algorithm = safe.addObject();
                copyText(objectMapper, item, algorithm, "algorithmId");
                copyText(objectMapper, item, algorithm, "name");
                copyText(objectMapper, item, algorithm, "type");
                copyText(objectMapper, item, algorithm, "defenseStage");
                copyText(objectMapper, item, algorithm, "defaultConfig", 400);
                copyText(objectMapper, item, algorithm, "configSchema", 400);
            }
        }
        JsonNode datasets = data.get("datasets");
        if (datasets != null && datasets.isArray()) {
            ArrayNode safe = evidence.putArray("datasets");
            int count = 0;
            for (JsonNode item : datasets) {
                if (count++ >= 5) {
                    break;
                }
                ObjectNode dataset = safe.addObject();
                copyText(objectMapper, item, dataset, "datasetId");
                copyText(objectMapper, item, dataset, "name");
                copyText(objectMapper, item, dataset, "type");
                copyText(objectMapper, item, dataset, "sampleCount");
                copyText(objectMapper, item, dataset, "featureCount");
                copyText(objectMapper, item, dataset, "classCount");
            }
        }
    }

    private static void copyText(ObjectMapper objectMapper, JsonNode source, ObjectNode target, String field) {
        copyText(objectMapper, source, target, field, 600);
    }

    private static void copyText(ObjectMapper objectMapper, JsonNode source, ObjectNode target,
                                  String field, int maxChars) {
        JsonNode value = source.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (value.isTextual()) {
            target.put(field, KnowledgeSanitizer.forModel(value.asText(), maxChars));
        } else if (value.isNumber() || value.isBoolean()) {
            target.set(field, value.deepCopy());
        } else if (value.isArray()) {
            ArrayNode safe = target.putArray(field);
            value.forEach(item -> {
                if (item.isNumber() || item.isBoolean()) {
                    safe.add(item.deepCopy());
                } else if (item.isTextual()) {
                    safe.add(KnowledgeSanitizer.forModel(item.asText(), Math.min(maxChars, 200)));
                }
            });
        } else {
            target.put(field, KnowledgeSanitizer.forModel(value.toString(), maxChars));
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
