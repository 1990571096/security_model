package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantScenarioCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void threeScenariosDeclareToolsCitationsAndFailureFallback() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(
                tool("validate_ldd_parameters", false), tool("get_parameter_guide", false),
                tool("search_knowledge", false), tool("preview_create_task", true),
                tool("get_my_task_summary", false), tool("preview_start_task", true),
                tool("get_my_report_summary", false)));
        AssistantScenarioCatalog catalog = new AssistantScenarioCatalog(registry);

        assertEquals(3, catalog.scenarios().size());
        AssistantScenarioCatalog.Scenario validation = catalog.find("ldd_parameter_validation");
        assertNotNull(validation);
        assertTrue(validation.tools().contains("validate_ldd_parameters"));
        assertTrue(validation.returnFields().contains("validation"));
        assertTrue(validation.returnFields().contains("citations"));
        assertTrue(validation.sideEffectPolicy().contains("不生成确认凭据"));
        assertEquals("validate_ldd_parameters", validation.requiredEvidenceTool());

        AssistantScenarioCatalog.Scenario preflight = catalog.find("task_preflight");
        assertNotNull(preflight);
        assertTrue(preflight.tools().contains("preview_start_task"));
        assertTrue(preflight.sideEffectPolicy().contains("允许保存确认与审计记录"));
        assertEquals("get_my_task_summary", preflight.requiredEvidenceTool());

        AssistantScenarioCatalog.Scenario explanation = catalog.find("result_explanation");
        assertNotNull(explanation);
        assertTrue(explanation.tools().contains("get_my_report_summary"));
        assertTrue(explanation.failureFallback().contains("metrics=unavailable"));
        assertTrue(explanation.sideEffectPolicy().contains("只读"));
    }

    @Test
    void modelPromptRoutesTheSameThreeScenariosAndKeepsCitations() {
        String prompt = DeepSeekAssistantModel.SYSTEM_PROMPT;
        assertTrue(prompt.contains("LDD 参数校验"));
        assertTrue(prompt.contains("任务预检"));
        assertTrue(prompt.contains("结果解释"));
        assertTrue(prompt.contains("preview_create_task"));
        assertTrue(prompt.contains("preview_start_task"));
        assertTrue(prompt.contains("citationId"));
        assertTrue(prompt.contains("不得编造"));
    }

    private AssistantTool tool(String name, boolean write) {
        return new AssistantTool() {
            @Override
            public AssistantToolDescriptor descriptor() {
                return new AssistantToolDescriptor(name, "scenario probe", write,
                        AssistantSchemas.object());
            }

            @Override
            public AssistantToolResult execute(JsonNode arguments) {
                throw new UnsupportedOperationException("probe must not execute");
            }
        };
    }
}
