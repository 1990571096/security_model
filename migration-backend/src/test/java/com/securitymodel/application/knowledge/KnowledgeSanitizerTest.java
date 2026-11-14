package com.securitymodel.application.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeSanitizerTest {

    @Test
    void redactsPathsCredentialsJwtAndEmail() {
        String raw = "路径 F:\\Study\\secret\\x.txt 和 /home/user/private.txt "
                + "token=abcdefgh12345678 Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature123 "
                + "mail a@b.com";
        String sanitized = KnowledgeSanitizer.sanitize(raw);

        assertFalse(sanitized.contains("F:\\Study"));
        assertFalse(sanitized.contains("/home/user"));
        assertFalse(sanitized.contains("abcdefgh12345678"));
        assertFalse(sanitized.contains("eyJhbGci"));
        assertFalse(sanitized.contains("a@b.com"));
        assertTrue(sanitized.contains("[已过滤"));
    }

    @Test
    void preservesApiPathsThatAreNotFilesystemPaths() {
        String sanitized = KnowledgeSanitizer.sanitize("POST /api/knowledge/query 返回引用");
        assertTrue(sanitized.contains("/api/knowledge/query"));
    }

    @Test
    void capsOutboundText() {
        String value = KnowledgeSanitizer.forModel("x".repeat(1000), 120);
        assertTrue(value.length() <= 120);
    }
}
