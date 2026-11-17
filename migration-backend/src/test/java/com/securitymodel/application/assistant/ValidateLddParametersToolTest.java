package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.task.PreDefenseTaskCommand;
import com.securitymodel.application.task.PreDefenseTaskCommandService;
import com.securitymodel.application.task.TaskCommandException;
import com.securitymodel.application.task.TaskExecutionPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidateLddParametersToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validParametersReturnNormalizedServerConfigurationWithoutConfirmation() {
        PreDefenseTaskCommandService commandService = mock(PreDefenseTaskCommandService.class);
        when(commandService.validateDraft(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ObjectNode finalParameters = objectMapper.createObjectNode()
                .put("batch_size", 128).put("num_epochs", 5)
                .put("data_path", "C:\\private\\dataset");
        ObjectNode preview = objectMapper.createObjectNode().put("configFingerprint", "fp");
        preview.set("finalParameters", finalParameters);
        preview.putObject("dataset").put("datasetId", 4).put("name", "cifar10");
        preview.putObject("algorithm").put("algorithmId", 14).put("name", "LDD");
        when(commandService.plan(any(PreDefenseTaskCommand.class))).thenReturn(
                new TaskExecutionPlan("fp", finalParameters, preview));

        ObjectNode arguments = objectMapper.createObjectNode()
                .put("datasetId", 4).put("algorithmId", 14);
        arguments.putObject("customParams").put("batch_size", 128).put("num_epochs", 5);
        AssistantToolResult result = new ValidateLddParametersTool(commandService, objectMapper)
                .execute(arguments);

        assertTrue(result.data().path("valid").asBoolean());
        assertEquals("PARAMETERS_VALID", result.data().path("code").asText());
        assertEquals("LDD_CONFIGURATION_POLICY", result.data().path("parameterSource").asText());
        assertEquals(128, result.data().path("normalizedParameters").path("batch_size").asInt());
        assertFalse(result.data().path("normalizedParameters").has("data_path"));
        assertFalse(result.data().path("confirmationCreated").asBoolean());
        assertEquals("none", result.data().path("sideEffect").asText());
    }

    @Test
    void outOfRangeUnknownNonLddAndUnavailableResourcesKeepStableCodes() {
        assertInvalid("PARAMETER_OUT_OF_RANGE", "LDD 参数 batch_size 必须是范围为 [16, 512] 的整数");
        assertInvalid("PARAMETER_UNKNOWN", "LDD 不支持参数: injected");
        assertInvalid("NOT_LDD_ALGORITHM", "当前平台仅开放已启用的 LDD 训练前防御算法");
        assertInvalid("DATASET_UNAVAILABLE", "指定的数据集不存在或未解析成功");
    }

    private void assertInvalid(String code, String message) {
        PreDefenseTaskCommandService commandService = mock(PreDefenseTaskCommandService.class);
        when(commandService.validateDraft(any()))
                .thenThrow(new TaskCommandException(code, message));
        AssistantToolResult result = new ValidateLddParametersTool(commandService, objectMapper)
                .execute(objectMapper.createObjectNode().put("datasetId", 4).put("algorithmId", 14));

        assertFalse(result.data().path("valid").asBoolean());
        assertEquals(code, result.data().path("code").asText());
        assertTrue(result.summary().contains("参数校验未通过"));
        assertFalse(result.data().path("confirmationCreated").asBoolean());
        assertEquals("none", result.data().path("sideEffect").asText());
    }
}
