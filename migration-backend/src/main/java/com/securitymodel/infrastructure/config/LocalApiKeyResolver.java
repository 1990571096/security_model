package com.securitymodel.infrastructure.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Reads a local secret file without ever logging its content. */
public final class LocalApiKeyResolver {

    private static final int MAX_SECRET_BYTES = 4096;

    private LocalApiKeyResolver() {
    }

    public static String resolve(String inline, String file) {
        String direct = inline == null ? "" : inline.trim();
        if (!direct.isBlank()) {
            return direct;
        }
        if (file == null || file.isBlank()) {
            return "";
        }
        Path path = Path.of(file.trim());
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            if (Files.size(path) > MAX_SECRET_BYTES) {
                return "";
            }
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return Objects.requireNonNullElse(content, "").trim();
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }
}
