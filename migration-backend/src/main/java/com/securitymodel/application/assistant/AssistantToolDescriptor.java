package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Explicit, typed tool contract exposed to the model; write tools never execute here. */
public record AssistantToolDescriptor(String name, String description, boolean write,
                                      ObjectNode inputSchema) {
}
