package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;

public interface AssistantTool {

    AssistantToolDescriptor descriptor();

    AssistantToolResult execute(JsonNode arguments);
}
