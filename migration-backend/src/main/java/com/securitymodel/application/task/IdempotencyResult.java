package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.JsonNode;

public record IdempotencyResult(int status, JsonNode body) {
}
