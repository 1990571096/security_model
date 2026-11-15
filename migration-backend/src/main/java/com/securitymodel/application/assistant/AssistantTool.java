package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;

public interface AssistantTool {

    AssistantToolDescriptor descriptor();

    AssistantToolResult execute(JsonNode arguments);

    /**
     * Executes with the remaining server budget. Implementations that perform I/O should
     * honor this value; the orchestrator also bounds the call so a broken tool cannot hold
     * the request slot indefinitely.
     */
    default AssistantToolResult execute(JsonNode arguments, long timeoutMillis) {
        return execute(arguments);
    }
}
