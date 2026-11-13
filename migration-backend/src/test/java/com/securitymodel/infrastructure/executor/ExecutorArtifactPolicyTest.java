package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorArtifactPolicyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void oversizedLogKeepsOnlyTheTail(@TempDir Path taskDir) throws Exception {
        Path stdout = taskDir.resolve("stdout.log");
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 500; index++) {
            content.append("line-").append(index).append('\n');
        }
        Files.writeString(stdout, content.toString(), StandardCharsets.UTF_8);
        long originalSize = Files.size(stdout);

        new ExecutorArtifactPolicy(objectMapper, 120).finalizeAttempt(taskDir);

        String trimmed = Files.readString(stdout, StandardCharsets.UTF_8);
        assertTrue(trimmed.startsWith("[truncated: kept last 120 of " + originalSize + " bytes]"));
        assertTrue(trimmed.contains("line-499"));
        assertTrue(Files.size(stdout) < originalSize);
    }

    @Test
    void smallLogIsLeftUntouched(@TempDir Path taskDir) throws Exception {
        Path stderr = taskDir.resolve("stderr.log");
        Files.writeString(stderr, "ok\n", StandardCharsets.UTF_8);

        new ExecutorArtifactPolicy(objectMapper, 1024).finalizeAttempt(taskDir);

        assertEquals("ok\n", Files.readString(stderr, StandardCharsets.UTF_8));
    }

    @Test
    void manifestListsEveryAttemptFileWithSha256(@TempDir Path taskDir) throws Exception {
        Files.writeString(taskDir.resolve("result.json"), "{\"status\":\"success\"}", StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("stdout.log"), "training\n", StandardCharsets.UTF_8);
        Files.writeString(taskDir.resolve("request.json"), "{}", StandardCharsets.UTF_8);

        new ExecutorArtifactPolicy(objectMapper, 1024).finalizeAttempt(taskDir);

        JsonNode manifest = objectMapper.readTree(Files.readString(
                taskDir.resolve("manifest.json"), StandardCharsets.UTF_8));
        assertEquals("SHA-256", manifest.get("algorithm").asText());
        assertEquals(3, manifest.get("files").size());
        for (JsonNode file : manifest.get("files")) {
            assertEquals(64, file.get("sha256").asText().length());
            assertTrue(file.get("bytes").asLong() > 0);
        }
    }
}
