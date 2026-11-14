package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantModelEvidenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsMetricsAndCitationsButNotRawPathsOrCredentials() {
        ObjectNode data = objectMapper.createObjectNode();
        data.put("taskId", 7);
        data.put("status", "succeeded");
        data.put("taskName", "F:/secret/run.log");
        data.put("customParams", "{\"api_key\":\"sk-1234567890abcdef\"}");
        data.put("cleanPurityPercentByIsolationRatio", 91.5);
        ObjectNode citation = data.putArray("citations").addObject();
        citation.put("citationId", "c1");
        citation.put("snippet", "见 F:/private/log.txt，联系 13800138000");
        citation.put("startLine", 10);
        citation.put("endLine", 12);

        String evidence = AssistantModelEvidence.forTool(objectMapper, "get_my_report_summary",
                "任务已成功", data);

        assertTrue(evidence.contains("91.5"));
        assertTrue(evidence.contains("c1"));
        assertTrue(evidence.contains("startLine"));
        assertFalse(evidence.contains("F:/secret"));
        assertFalse(evidence.contains("F:/private"));
        assertFalse(evidence.contains("sk-1234567890abcdef"));
        assertFalse(evidence.contains("13800138000"));
    }

    @Test
    void realLddSchemaKeepsBatchAndEpochConstraintsWithinBudget() throws Exception {
        java.nio.file.Path seed = java.nio.file.Path.of("..", "sql", "security_model_vab_seed.sql");
        String sql = java.nio.file.Files.readString(seed);
        String defaultConfig = quotedJson(sql, "{\"dataset\":\"cifar10\"");
        String configSchema = quotedJson(sql, "{\"dataset\":{\"type\":\"select\"");
        assertTrue(!defaultConfig.isBlank() && !configSchema.isBlank(), "LDD seed must contain real config");
        assertTrue(configSchema.length() > 400, "real LDD schema must be longer than the old truncation limit");

        ObjectNode data = objectMapper.createObjectNode();
        ObjectNode algorithm = data.putArray("algorithms").addObject();
        algorithm.put("algorithmId", 1);
        algorithm.put("name", "LDD (VaB)");
        algorithm.put("defenseStage", "pre");
        algorithm.put("defaultConfig", defaultConfig);
        algorithm.put("configSchema", configSchema);
        data.putArray("datasets").addObject().put("datasetId", 7).put("name", "cifar10");

        String evidence = AssistantModelEvidence.forTool(objectMapper, "get_parameter_guide",
                "可用算法 1 个", data);

        assertFalse(evidence.length() > 3200);
        ObjectNode parsed = (ObjectNode) objectMapper.readTree(evidence);
        assertFalse(parsed.path("truncated").asBoolean());
        ObjectNode parameters = (ObjectNode) parsed.path("evidence").path("algorithms").get(0).path("parameters");
        assertEquals(16, parameters.path("batch_size").path("min").asInt());
        assertEquals(512, parameters.path("batch_size").path("max").asInt());
        assertEquals(128, parameters.path("batch_size").path("default").asInt());
        assertEquals(1, parameters.path("num_epochs").path("min").asInt());
        assertEquals(200, parameters.path("num_epochs").path("max").asInt());
        assertEquals(5, parameters.path("num_epochs").path("default").asInt());
    }

    private String quotedJson(String sql, String prefix) {
        int start = sql.indexOf("'" + prefix);
        if (start < 0) {
            return "";
        }
        start++;
        int end = sql.indexOf("'", start);
        return end < 0 ? "" : sql.substring(start, end);
    }

    @Test
    void parameterGuideKeepsBatchAndEpochDefaultsAndValidJson() throws Exception {
        ObjectNode data = objectMapper.createObjectNode();
        ObjectNode algorithm = data.putArray("algorithms").addObject();
        algorithm.put("algorithmId", 1);
        algorithm.put("name", "LDD");
        algorithm.put("defenseStage", "pre");
        algorithm.put("defaultConfig", "{\"batch_size\":128,\"num_epochs\":5}");
        algorithm.put("configSchema", "{\"batch_size\":{\"type\":\"integer\",\"minimum\":1},"
                + "\"num_epochs\":{\"type\":\"integer\",\"maximum\":20}}");
        ObjectNode dataset = data.putArray("datasets").addObject();
        dataset.put("datasetId", 7);
        dataset.put("name", "cifar10");
        dataset.put("type", "image");

        String evidence = AssistantModelEvidence.forTool(objectMapper, "get_parameter_guide",
                "可用算法 1 个", data);

        assertTrue(evidence.contains("batch_size"));
        assertTrue(evidence.contains("128"));
        assertTrue(evidence.contains("num_epochs"));
        assertTrue(evidence.contains("5"));
        assertTrue(evidence.contains("cifar10"));
        objectMapper.readTree(evidence);
    }

    @Test
    void oversizedEvidenceFallsBackToValidJsonInsteadOfCuttingStructure() throws Exception {
        ObjectNode data = objectMapper.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode citations = data.putArray("citations");
        for (int i = 0; i < 10; i++) {
            ObjectNode citation = citations.addObject();
            citation.put("citationId", "c" + i);
            citation.put("snippet", "x".repeat(600));
        }

        String evidence = AssistantModelEvidence.forTool(objectMapper, "search_knowledge",
                "大量引用", data);

        ObjectNode parsed = (ObjectNode) objectMapper.readTree(evidence);
        assertTrue(parsed.path("truncated").asBoolean());
        assertFalse(evidence.contains("x".repeat(50)));
    }
}
