package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantArgsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsIntegralNumberAndExactDecimalString() throws Exception {
        JsonNode number = objectMapper.readTree("{\"taskId\":9007199254740993}");
        JsonNode text = objectMapper.readTree("{\"taskId\":\"9007199254740993\"}");

        assertEquals(9007199254740993L, AssistantArgs.requireLong(number, "taskId"));
        assertEquals(9007199254740993L, AssistantArgs.requireLong(text, "taskId"));
    }

    @Test
    void rejectsMalformedOrOversizedStringIdentifier() throws Exception {
        assertInvalid("{\"taskId\":\"12a\"}", "taskId");
        assertInvalid("{\"taskId\":\"12345678901234567890\"}", "taskId");
    }

    @Test
    void rejectsNumericIdentifierBeyondLongRange() throws Exception {
        assertInvalid("{\"taskId\":123456789012345678901234567890}", "taskId");
    }

    private void assertInvalid(String json, String field) throws Exception {
        JsonNode arguments = objectMapper.readTree(json);
        AssistantException exception = assertThrows(AssistantException.class,
                () -> AssistantArgs.requireLong(arguments, field));
        assertEquals("ASSISTANT_TOOL_ARGUMENTS_INVALID", exception.getCode());
    }
}
