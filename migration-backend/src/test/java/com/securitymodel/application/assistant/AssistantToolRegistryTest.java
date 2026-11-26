package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantToolRegistryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void unknownToolIsNotAllowed() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(tool("read_a", false)));
        assertThrows(AssistantException.class, () -> registry.find("shell"));
    }

    @Test
    void duplicateToolNamesFailAtStartup() {
        assertThrows(IllegalStateException.class, () -> new AssistantToolRegistry(
                List.of(tool("read_a", false), tool("read_a", true))));
    }

    @Test
    void descriptorsExposeWriteFlagAndSchema() {
        AssistantToolRegistry registry = new AssistantToolRegistry(List.of(
                tool("read_a", false), tool("write_a", true)));
        assertEquals(2, registry.descriptors().size());
        assertEquals(true, registry.descriptors().get(1).write());
        assertEquals("object", registry.descriptors().get(1).inputSchema().path("type").asText());
    }

    private AssistantTool tool(String name, boolean write) {
        return new AssistantTool() {
            @Override
            public AssistantToolDescriptor descriptor() {
                return new AssistantToolDescriptor(name, name, write, AssistantSchemas.object());
            }

            @Override
            public AssistantToolResult execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return new AssistantToolResult("ok", objectMapper.createObjectNode(), null, null,
                        null, null, null);
            }
        };
    }
}
