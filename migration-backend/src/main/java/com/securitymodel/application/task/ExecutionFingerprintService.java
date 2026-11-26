package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.ZoneId;

@Component
public class ExecutionFingerprintService {
    private final ObjectMapper objectMapper;
    private final String workspace;
    private final String pythonExecutable;
    private final String pythonScript;
    private final boolean fingerprintContent;
    private final long maxBytes;

    public ExecutionFingerprintService(
            ObjectMapper objectMapper,
            @Value("${security-model.executor.python.workspace:${java.io.tmpdir}/security-model-executor}") String workspace,
            @Value("${security-model.executor.python.executable:python}") String pythonExecutable,
            @Value("${security-model.executor.python.script:}") String pythonScript,
            @Value("${security-model.executor.python.fingerprint-content:true}") boolean fingerprintContent,
            @Value("${security-model.executor.python.fingerprint-max-bytes:1073741824}") long maxBytes) {
        this.objectMapper = objectMapper;
        this.workspace = workspace;
        this.pythonExecutable = pythonExecutable;
        this.pythonScript = pythonScript;
        this.fingerprintContent = fingerprintContent;
        this.maxBytes = maxBytes;
    }

    public String getWorkspace() {
        return workspace;
    }

    public ObjectNode buildRuntimeSnapshot(DefenseTaskRequest request) {
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.put("snapshotSchema", "security-model.execution.v2");
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("javaVendor", System.getProperty("java.vendor"));
        runtime.put("osName", System.getProperty("os.name"));
        runtime.put("osVersion", System.getProperty("os.version"));
        runtime.put("osArch", System.getProperty("os.arch"));
        runtime.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        runtime.put("timezone", ZoneId.systemDefault().toString());
        runtime.put("pythonExecutable", pythonExecutable);
        runtime.put("pythonScript", pythonScript);
        runtime.put("executorWorkspace", workspace);
        runtime.set("datasetFingerprint", fingerprint(request.getDataset()));
        runtime.set("algorithmFingerprint", fingerprint(request.getAlgorithm()));
        runtime.set("hyperParameterFingerprint", fingerprint(request.getHyperParameter()));
        return runtime;
    }

    public String sha256(String value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算快照摘要", exception);
        }
    }

    private ObjectNode fingerprint(DefenseResourceRef resource) {
        ObjectNode result = objectMapper.createObjectNode();
        if (resource == null) {
            result.put("available", false);
            return result;
        }
        result.put("resourceId", resource.getId());
        result.put("name", resource.getName());
        result.put("type", resource.getType());
        result.put("path", resource.getPath());
        if (!StringUtils.hasText(resource.getPath())) {
            result.put("available", false);
            result.put("kind", "missing_path");
            return result;
        }
        if (resource.getPath().matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            result.put("kind", "logical_uri");
            result.put("available", true);
            return result;
        }
        try {
            Path path = Paths.get(resource.getPath()).toAbsolutePath().normalize();
            result.put("normalizedPath", path.toString());
            result.put("exists", Files.exists(path));
            if (!Files.exists(path)) {
                result.put("available", false);
                return result;
            }
            Path real = path.toRealPath();
            result.put("realPath", real.toString());
            result.put("kind", Files.isDirectory(real) ? "directory" : "file");
            result.put("size", Files.isRegularFile(real) ? Files.size(real) : 0);
            result.put("lastModified", Files.getLastModifiedTime(real).toMillis());
            if (Files.isRegularFile(real) && fingerprintContent && Files.size(real) <= maxBytes) {
                result.put("contentSha256", sha256(real));
            }
            result.put("available", true);
        } catch (Exception exception) {
            result.put("available", false);
            result.put("error", exception.getClass().getSimpleName());
        }
        return result;
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return hex(digest.digest());
        } catch (Exception exception) {
            throw new IOException("无法计算文件摘要", exception);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }
}
