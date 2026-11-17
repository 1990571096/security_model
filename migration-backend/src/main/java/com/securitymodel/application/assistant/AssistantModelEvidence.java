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
        copyText(objectMapper, data, evidence, "taskIdText", 80);
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
        if ("validate_ldd_parameters".equals(toolName)) {
            copyValidationEvidence(objectMapper, data, evidence);
        }
        if ("get_my_task_summary".equals(toolName)) {
            copyPreflightEvidence(objectMapper, data, evidence);
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
                // Keep parameter constraints structured. Serializing configSchema as a
                // string and cutting it at a fixed offset drops min/max/options that come
                // after that offset (the real LDD schema is longer than 400 chars).
                copyProjectedParameters(objectMapper, item, algorithm);
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

    private static void copyProjectedParameters(ObjectMapper objectMapper, JsonNode source,
                                                 ObjectNode target) {
        JsonNode schema = parseObject(objectMapper, source.get("configSchema"));
        JsonNode defaults = parseObject(objectMapper, source.get("defaultConfig"));
        ObjectNode projected = target.putObject("parameters");
        int count = 0;
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        // The model needs the parameters that actually determine an LDD run first.
        keys.addAll(java.util.List.of("batch_size", "num_epochs", "dataset", "trigger_type",
                "trigger_label", "poisoned_mode", "poisoned_portion", "seed"));
        if (schema != null && schema.isObject()) {
            schema.fieldNames().forEachRemaining(keys::add);
        }
        if (defaults != null && defaults.isObject()) {
            defaults.fieldNames().forEachRemaining(keys::add);
        }
        for (String key : keys) {
            if (count++ >= 30) {
                break;
            }
            ObjectNode parameter = projected.putObject(key);
            JsonNode meta = schema != null && schema.isObject() ? schema.get(key) : null;
            if (meta != null && meta.isObject()) {
                copyText(objectMapper, meta, parameter, "type");
                copyValue(objectMapper, meta, parameter, "default", 120);
                copyValue(objectMapper, meta, parameter, "min", 120);
                copyValue(objectMapper, meta, parameter, "max", 120);
                copyOptions(objectMapper, meta.get("options"), parameter);
            }
            JsonNode defaultValue = meta != null && meta.has("default")
                    ? meta.get("default")
                    : defaults != null ? defaults.get(key) : null;
            if (defaultValue != null && !defaultValue.isNull()) {
                copySanitizedValue(objectMapper, defaultValue, parameter, "default", 120);
            }
        }
    }

    private static JsonNode parseObject(ObjectMapper objectMapper, JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return null;
        }
        JsonNode value = raw.isTextual() ? parseText(objectMapper, raw.asText()) : raw;
        return value != null && value.isObject() ? value : null;
    }

    private static JsonNode parseText(ObjectMapper objectMapper, String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            return null;
        }
    }

    private static void copyOptions(ObjectMapper objectMapper, JsonNode options, ObjectNode target) {
        if (options == null || !options.isArray()) {
            return;
        }
        ArrayNode safe = target.putArray("options");
        int count = 0;
        for (JsonNode option : options) {
            if (count++ >= 20) {
                break;
            }
            copySanitizedValue(objectMapper, option, safe, null, 80);
        }
    }

    private static void copyValue(ObjectMapper objectMapper, JsonNode source, ObjectNode target,
                                  String field, int maxChars) {
        JsonNode value = source.get(field);
        if (value != null && !value.isNull()) {
            copySanitizedValue(objectMapper, value, target, field, maxChars);
        }
    }

    private static void copySanitizedValue(ObjectMapper objectMapper, JsonNode value, ObjectNode target,
                                           String field, int maxChars) {
        JsonNode safe = sanitizeValue(objectMapper, value, maxChars);
        if (safe == null || field == null) {
            return;
        }
        target.set(field, safe);
    }

    private static void copySanitizedValue(ObjectMapper objectMapper, JsonNode value, ArrayNode target,
                                           String field, int maxChars) {
        JsonNode safe = sanitizeValue(objectMapper, value, maxChars);
        if (safe != null) {
            target.add(safe);
        }
    }

    private static JsonNode sanitizeValue(ObjectMapper objectMapper, JsonNode value, int maxChars) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.deepCopy();
        }
        if (value.isTextual()) {
            return objectMapper.getNodeFactory().textNode(
                    KnowledgeSanitizer.forModel(value.asText(), maxChars));
        }
        if (value.isArray()) {
            ArrayNode safe = objectMapper.createArrayNode();
            for (JsonNode item : value) {
                JsonNode child = sanitizeValue(objectMapper, item, Math.min(maxChars, 80));
                if (child != null) {
                    safe.add(child);
                }
            }
            return safe;
        }
        return objectMapper.getNodeFactory().textNode(
                KnowledgeSanitizer.forModel(value.toString(), maxChars));
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

    private static void copyValidationEvidence(ObjectMapper objectMapper, JsonNode data,
                                                ObjectNode evidence) {
        copyText(objectMapper, data, evidence, "valid");
        copyText(objectMapper, data, evidence, "code", 80);
        copyText(objectMapper, data, evidence, "message", 240);
        copyText(objectMapper, data, evidence, "parameterSource", 80);
        copyText(objectMapper, data, evidence, "configFingerprint", 120);
        copyText(objectMapper, data, evidence, "sideEffect", 40);
        copyText(objectMapper, data, evidence, "confirmationCreated");
        copyScalarParameters(objectMapper, data.get("normalizedParameters"), evidence);
    }

    private static void copyPreflightEvidence(ObjectMapper objectMapper, JsonNode data,
                                               ObjectNode evidence) {
        copyText(objectMapper, data, evidence, "executorMode", 40);
        copyText(objectMapper, data, evidence, "executorRole", 40);
        copyText(objectMapper, data, evidence, "executorConfiguration", 40);
        copyLimitedItems(objectMapper, data.get("blockers"), evidence, "blockers",
                "code", "message", 4);
        copyLimitedItems(objectMapper, data.get("warnings"), evidence, "warnings",
                "code", "message", 4);
    }

    /**
     * Keep only scalar LDD parameters. A nested object could smuggle raw paths or logs;
     * data_path is excluded even when a caller puts it back into the tool payload.
     */
    private static void copyScalarParameters(ObjectMapper objectMapper, JsonNode parameters,
                                              ObjectNode evidence) {
        if (parameters == null || !parameters.isObject()) {
            return;
        }
        ObjectNode safe = evidence.putObject("normalizedParameters");
        int count = 0;
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = parameters.fields();
        while (fields.hasNext() && count < 20) {
            java.util.Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();
            if ("data_path".equals(key) || value == null || value.isNull()
                    || value.isContainerNode()) {
                continue;
            }
            count++;
            if (value.isTextual()) {
                safe.put(key, KnowledgeSanitizer.forModel(value.asText(), 120));
            } else if (value.isNumber() || value.isBoolean()) {
                safe.set(key, value.deepCopy());
            }
        }
    }

    private static void copyLimitedItems(ObjectMapper objectMapper, JsonNode source,
                                          ObjectNode evidence, String field,
                                          String codeField, String messageField, int maxItems) {
        if (source == null || !source.isArray()) {
            return;
        }
        ArrayNode safe = evidence.putArray(field);
        int count = 0;
        for (JsonNode item : source) {
            if (count++ >= maxItems || !item.isObject()) {
                break;
            }
            ObjectNode projected = safe.addObject();
            copyText(objectMapper, item, projected, codeField, 80);
            copyText(objectMapper, item, projected, messageField, 180);
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
