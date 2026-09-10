package me.zhengjie.modules.system.service.snapshot;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import me.zhengjie.modules.system.service.executor.dto.DefenseResourceRef;
import me.zhengjie.modules.system.service.executor.dto.DefenseTaskRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TimeZone;

/**
 * Builds immutable execution-environment fingerprints for experiment snapshots.
 *
 * <p>The service only reads metadata and content digests. It never copies a
 * dataset or algorithm artifact into the application database.</p>
 */
@Component
public class ExecutionFingerprintService {

    private final String pythonExecutorWorkspace;
    private final String pythonExecutable;
    private final String pythonScript;
    private final boolean fingerprintContent;
    private final long fingerprintMaxBytes;

    public ExecutionFingerprintService(
            @Value("${security-model.executor.python.workspace:${java.io.tmpdir}/security-model-executor}")
            String pythonExecutorWorkspace,
            @Value("${security-model.executor.python.executable:python}") String pythonExecutable,
            @Value("${security-model.executor.python.script:}") String pythonScript,
            @Value("${security-model.executor.python.fingerprint-content:true}") boolean fingerprintContent,
            @Value("${security-model.executor.python.fingerprint-max-bytes:1073741824}")
            long fingerprintMaxBytes) {
        this.pythonExecutorWorkspace = pythonExecutorWorkspace;
        this.pythonExecutable = pythonExecutable;
        this.pythonScript = pythonScript;
        this.fingerprintContent = fingerprintContent;
        this.fingerprintMaxBytes = fingerprintMaxBytes;
    }

    public JSONObject buildRuntimeSnapshot(DefenseTaskRequest request) {
        JSONObject runtime = new JSONObject();
        runtime.put("snapshotSchema", "security-model.execution.v2");
        runtime.put("platform", "security-model");
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("javaVendor", System.getProperty("java.vendor"));
        runtime.put("osName", System.getProperty("os.name"));
        runtime.put("osVersion", System.getProperty("os.version"));
        runtime.put("osArch", System.getProperty("os.arch"));
        runtime.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        runtime.put("timezone", TimeZone.getDefault().getID());
        runtime.put("pythonExecutable", pythonExecutable);
        runtime.put("pythonScript", pythonScript);
        runtime.put("executorWorkspace", pythonExecutorWorkspace);
        runtime.put("fingerprintPolicy", buildFingerprintPolicy());
        runtime.put("pythonScriptFingerprint", buildPathFingerprint("python_script", pythonScript));
        runtime.put("datasetFingerprint", buildResourceFingerprint(request.getDataset()));
        runtime.put("algorithmFingerprint", buildResourceFingerprint(request.getAlgorithm()));
        runtime.put("hyperParameterFingerprint", buildResourceFingerprint(request.getHyperParameter()));
        return runtime;
    }

    public String getExecutorWorkspace() {
        return pythonExecutorWorkspace;
    }

    public String calculateSha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private JSONObject buildFingerprintPolicy() {
        JSONObject policy = new JSONObject();
        policy.put("contentHash", fingerprintContent);
        policy.put("maxContentHashBytes", fingerprintMaxBytes);
        policy.put("algorithm", "SHA-256");
        return policy;
    }

    private JSONObject buildResourceFingerprint(DefenseResourceRef resource) {
        JSONObject fingerprint = new JSONObject();
        if (resource == null) {
            fingerprint.put("available", false);
            return fingerprint;
        }
        fingerprint.put("resourceId", resource.getId());
        fingerprint.put("name", resource.getName());
        fingerprint.put("type", resource.getType());
        fingerprint.put("path", resource.getPath());
        if (isLogicalPath(resource.getPath())) {
            fingerprint.put("kind", "logical_uri");
            fingerprint.put("available", true);
            fingerprint.put("identityHash", calculateSha256(JSON.toJSONString(fingerprint)));
            return fingerprint;
        }

        JSONObject pathFingerprint = buildPathFingerprint(resource.getType(), resource.getPath());
        fingerprint.putAll(pathFingerprint);
        fingerprint.put("identityHash", calculateSha256(JSON.toJSONString(fingerprint)));
        return fingerprint;
    }

    private JSONObject buildPathFingerprint(String type, String rawPath) {
        JSONObject fingerprint = new JSONObject();
        fingerprint.put("pathType", type);
        fingerprint.put("path", rawPath);
        if (!StringUtils.hasText(rawPath)) {
            fingerprint.put("available", false);
            return fingerprint;
        }
        if (isLogicalPath(rawPath)) {
            fingerprint.put("kind", "logical_uri");
            fingerprint.put("available", true);
            return fingerprint;
        }
        try {
            Path path = Paths.get(rawPath).toAbsolutePath().normalize();
            fingerprint.put("normalizedPath", path.toString());
            boolean exists = Files.exists(path);
            fingerprint.put("exists", exists);
            if (!exists) {
                fingerprint.put("available", false);
                return fingerprint;
            }

            Path realPath = path.toRealPath();
            fingerprint.put("realPath", realPath.toString());
            fingerprint.put("kind", Files.isDirectory(realPath) ? "directory" : "file");
            fingerprint.put("size", Files.isRegularFile(realPath) ? Files.size(realPath) : null);
            fingerprint.put("lastModified", Files.getLastModifiedTime(realPath).toMillis());
            if (Files.isRegularFile(realPath)) {
                appendContentFingerprint(fingerprint, realPath);
            }
            fingerprint.put("available", true);
        } catch (Exception e) {
            fingerprint.put("available", false);
            fingerprint.put("error", e.getClass().getSimpleName());
        }
        return fingerprint;
    }

    private boolean isLogicalPath(String value) {
        return StringUtils.hasText(value) && value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    }

    private void appendContentFingerprint(JSONObject fingerprint, Path path) {
        if (!fingerprintContent) {
            fingerprint.put("contentHashStatus", "disabled");
            return;
        }
        try {
            long size = Files.size(path);
            if (fingerprintMaxBytes > 0 && size > fingerprintMaxBytes) {
                fingerprint.put("contentHashStatus", "skipped_size_limit");
                fingerprint.put("contentHashLimitBytes", fingerprintMaxBytes);
                return;
            }
            fingerprint.put("contentSha256", calculateSha256(path));
            fingerprint.put("contentHashStatus", "available");
        } catch (IOException e) {
            fingerprint.put("contentHashStatus", "failed");
            fingerprint.put("contentHashError", e.getClass().getSimpleName());
        }
    }

    private String calculateSha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024 * 1024];
            try (InputStream input = Files.newInputStream(path)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String toHex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            hex.append(String.format("%02x", item));
        }
        return hex.toString();
    }
}
