package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** Read-only parameter/catalog guidance; file paths are never copied into the tool result. */
@Component
public class ParameterGuideTool implements AssistantTool {

    private static final int MAX_ITEMS = 10;
    private final ResourceQueryService resourceQueryService;
    private final ObjectMapper objectMapper;

    public ParameterGuideTool(ResourceQueryService resourceQueryService, ObjectMapper objectMapper) {
        this.resourceQueryService = resourceQueryService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.integer(schema, "algorithmId", "可选，限定某个算法", false);
        return new AssistantToolDescriptor("get_parameter_guide",
                "读取已启用算法、默认参数、参数 schema 和可用数据集元数据；不读取文件路径",
                false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("algorithmId"));
        Long algorithmId = AssistantArgs.optionalLong(arguments, "algorithmId");

        ArrayNode algorithms = objectMapper.createArrayNode();
        List<Algorithm> available = resourceQueryService.findAvailableAlgorithms("pre", null);
        for (Algorithm algorithm : available) {
            if (algorithmId != null && !algorithmId.equals(algorithm.getAlgorithmId())) {
                continue;
            }
            ObjectNode item = algorithms.addObject();
            item.put("algorithmId", algorithm.getAlgorithmId());
            item.put("name", algorithm.getAlgorithmName());
            item.put("type", algorithm.getAlgorithmType());
            item.put("category", algorithm.getAlgorithmCategory());
            item.put("defenseStage", algorithm.getDefenseStage());
            item.put("description", KnowledgeSanitizer.forModel(algorithm.getDescription(), 300));
            putJson(item, "defaultConfig", algorithm.getDefaultConfig());
            putJson(item, "configSchema", algorithm.getConfigSchema());
            if (algorithms.size() >= MAX_ITEMS) {
                break;
            }
        }

        ArrayNode datasets = objectMapper.createArrayNode();
        for (Dataset dataset : resourceQueryService.findAvailableDatasets(null)) {
            ObjectNode item = datasets.addObject();
            item.put("datasetId", dataset.getDatasetId());
            item.put("name", dataset.getDatasetName());
            item.put("type", dataset.getDatasetType());
            item.put("sampleCount", dataset.getSampleCount());
            item.put("featureCount", dataset.getFeatureCount());
            item.put("classCount", dataset.getClassCount());
            if (datasets.size() >= MAX_ITEMS) {
                break;
            }
        }

        ObjectNode data = objectMapper.createObjectNode();
        data.set("algorithms", algorithms);
        data.set("datasets", datasets);
        String summary = "可用算法 " + algorithms.size() + " 个，可用数据集 " + datasets.size()
                + " 个；返回默认参数与 schema，不包含本机路径。";
        return new AssistantToolResult(summary, data, null, null, null, null, null);
    }

    private void putJson(ObjectNode target, String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            target.set(field, node);
        } catch (Exception ignored) {
            // Malformed catalog JSON is omitted instead of exposing a parser detail.
        }
    }
}
