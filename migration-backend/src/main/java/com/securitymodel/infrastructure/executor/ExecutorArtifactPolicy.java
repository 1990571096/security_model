package com.securitymodel.infrastructure.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Bounds and indexes one executor attempt directory. Everything here is best-effort:
 * a manifest or truncation failure never changes the task outcome.
 */
@Service
public class ExecutorArtifactPolicy {

    private static final Logger log = LoggerFactory.getLogger(ExecutorArtifactPolicy.class);
    private static final String MANIFEST = "manifest.json";

    private final ObjectMapper objectMapper;
    private final long maxLogBytes;

    public ExecutorArtifactPolicy(
            ObjectMapper objectMapper,
            @Value("${security-model.executor.artifacts.max-log-bytes:20971520}") long maxLogBytes) {
        this.objectMapper = objectMapper;
        this.maxLogBytes = maxLogBytes <= 0 ? 0 : maxLogBytes;
    }

    /** Trim oversized logs, then write a SHA-256 manifest for the attempt directory. */
    public void finalizeAttempt(Path taskDir) {
        if (taskDir == null || !Files.isDirectory(taskDir)) {
            return;
        }
        trimLog(taskDir.resolve("stdout.log"));
        trimLog(taskDir.resolve("stderr.log"));
        writeManifest(taskDir);
    }

    private void trimLog(Path logFile) {
        if (maxLogBytes <= 0 || !Files.isRegularFile(logFile)) {
            return;
        }
        try {
            long size = Files.size(logFile);
            if (size <= maxLogBytes) {
                return;
            }
            byte[] tail = new byte[(int) maxLogBytes];
            try (RandomAccessFile reader = new RandomAccessFile(logFile.toFile(), "r")) {
                reader.seek(size - maxLogBytes);
                reader.readFully(tail);
            }
            String marker = "[truncated: kept last " + maxLogBytes + " of " + size + " bytes]\n";
            Path replacement = logFile.resolveSibling(logFile.getFileName() + ".trimmed");
            Files.writeString(replacement, marker, StandardCharsets.UTF_8);
            try (var channel = java.nio.channels.FileChannel.open(replacement,
                    java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.APPEND)) {
                channel.write(java.nio.ByteBuffer.wrap(tail));
            }
            Files.move(replacement, logFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Truncated executor log: file={}, keptBytes={}", logFile, maxLogBytes);
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not trim executor log: file={}, reason={}", logFile, exception.getMessage());
        }
    }

    private void writeManifest(Path taskDir) {
        try {
            ArrayNode files = objectMapper.createArrayNode();
            List<Path> entries;
            try (Stream<Path> stream = Files.walk(taskDir, 1)) {
                entries = stream.filter(Files::isRegularFile)
                        .filter(path -> !MANIFEST.equals(path.getFileName().toString()))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
            for (Path entry : entries) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("file", entry.getFileName().toString());
                node.put("bytes", Files.size(entry));
                node.put("sha256", sha256(entry));
                files.add(node);
            }
            ObjectNode manifest = objectMapper.createObjectNode();
            manifest.put("algorithm", "SHA-256");
            manifest.put("generated_at", java.time.OffsetDateTime.now().toString());
            manifest.set("files", files);
            Files.writeString(taskDir.resolve(MANIFEST),
                    objectMapper.writeValueAsString(manifest), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException exception) {
            log.warn("Could not write executor artifact manifest: dir={}, reason={}",
                    taskDir, exception.getMessage());
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
