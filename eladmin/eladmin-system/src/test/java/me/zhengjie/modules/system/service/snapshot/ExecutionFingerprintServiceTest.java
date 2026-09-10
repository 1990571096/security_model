package me.zhengjie.modules.system.service.snapshot;

import com.alibaba.fastjson2.JSONObject;
import me.zhengjie.modules.system.service.executor.dto.DefenseResourceRef;
import me.zhengjie.modules.system.service.executor.dto.DefenseTaskRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionFingerprintServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsContentHashForSmallDatasetFile() throws IOException {
        Path dataset = Files.write(
                temporaryDirectory.resolve("dataset.bin"),
                "ldd-demo".getBytes(StandardCharsets.UTF_8));
        ExecutionFingerprintService service = new ExecutionFingerprintService(
                temporaryDirectory.toString(), "python", "", true, 1024);

        JSONObject runtime = service.buildRuntimeSnapshot(DefenseTaskRequest.builder()
                .dataset(DefenseResourceRef.builder()
                        .id(1L).name("demo").type("image").path(dataset.toString()).build())
                .build());

        JSONObject fingerprint = runtime.getJSONObject("datasetFingerprint");
        assertTrue(fingerprint.getBooleanValue("available"));
        assertEquals("available", fingerprint.getString("contentHashStatus"));
        assertEquals(service.calculateSha256("ldd-demo"), fingerprint.getString("contentSha256"));
        assertNotNull(fingerprint.getString("identityHash"));
    }

    @Test
    void skipsLargeFileContentHashButRetainsMetadata() throws IOException {
        Path dataset = Files.write(
                temporaryDirectory.resolve("dataset.bin"),
                "too-large".getBytes(StandardCharsets.UTF_8));
        ExecutionFingerprintService service = new ExecutionFingerprintService(
                temporaryDirectory.toString(), "python", "", true, 1);

        JSONObject runtime = service.buildRuntimeSnapshot(DefenseTaskRequest.builder()
                .dataset(DefenseResourceRef.builder()
                        .id(1L).name("demo").type("image").path(dataset.toString()).build())
                .build());

        JSONObject fingerprint = runtime.getJSONObject("datasetFingerprint");
        assertTrue(fingerprint.getBooleanValue("available"));
        assertEquals("skipped_size_limit", fingerprint.getString("contentHashStatus"));
        assertEquals(1L, fingerprint.getLongValue("contentHashLimitBytes"));
    }

    @Test
    void treatsImplementationUrisAsLogicalResources() {
        ExecutionFingerprintService service = new ExecutionFingerprintService(
                temporaryDirectory.toString(), "python", "", false, 0);

        JSONObject runtime = service.buildRuntimeSnapshot(DefenseTaskRequest.builder()
                .algorithm(DefenseResourceRef.builder()
                        .id(2L).name("LDD").type("pre_defense")
                        .path("vab://pre-defense/ldd").build())
                .build());

        JSONObject fingerprint = runtime.getJSONObject("algorithmFingerprint");
        assertTrue(fingerprint.getBooleanValue("available"));
        assertEquals("logical_uri", fingerprint.getString("kind"));
        assertFalse(fingerprint.containsKey("error"));
    }
}
