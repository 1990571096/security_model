package com.securitymodel.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalApiKeyResolverTest {

    @Test
    void inlineKeyWins() {
        assertEquals("inline-key", LocalApiKeyResolver.resolve(" inline-key ", "ignored.txt"));
    }

    @Test
    void readsTrimmedFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("key.txt");
        Files.writeString(file, "  sk-file-key\n", StandardCharsets.UTF_8);

        assertEquals("sk-file-key", LocalApiKeyResolver.resolve("", file.toString()));
    }

    @Test
    void missingOrOversizedFileReturnsEmpty(@TempDir Path directory) throws Exception {
        assertEquals("", LocalApiKeyResolver.resolve(null, directory.resolve("missing.txt").toString()));

        Path oversized = directory.resolve("oversized.txt");
        Files.write(oversized, new byte[5000]);
        assertEquals("", LocalApiKeyResolver.resolve("", oversized.toString()));
    }
}
