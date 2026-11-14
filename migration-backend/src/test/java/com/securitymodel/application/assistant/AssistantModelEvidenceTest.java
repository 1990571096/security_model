package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

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
}
