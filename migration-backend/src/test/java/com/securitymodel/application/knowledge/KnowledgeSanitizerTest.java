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
    void redactsForwardSlashPathsAndJsonShapedCredentials() {
        String raw = "artifact F:/Study/secret/result.json "
                + "\"api_key\": \"sk-abcdefgh12345678\", \"password\":\"hunter2hunter2\" "
                + "手机 13800138000 身份证 11010519491231002X";
        String sanitized = KnowledgeSanitizer.sanitize(raw);

        assertFalse(sanitized.contains("F:/Study"));
        assertFalse(sanitized.contains("sk-abcdefgh12345678"));
        assertFalse(sanitized.contains("hunter2hunter2"));
        assertFalse(sanitized.contains("13800138000"));
        assertFalse(sanitized.contains("11010519491231002X"));
        assertTrue(sanitized.contains("[已过滤"));
    }

    @Test
    void redactsWorkspaceAndDataPathsAndShortPlainCredentials() {
        String raw = "cwd /workspace/project/run.log data /data/raw/sample.bin "
                + "password=abc123 token: xyz789";
        String sanitized = KnowledgeSanitizer.sanitize(raw);

        assertFalse(sanitized.contains("/workspace"));
        assertFalse(sanitized.contains("/data/raw"));
        assertFalse(sanitized.contains("abc123"));
        assertFalse(sanitized.contains("xyz789"));
    }

    @Test
    void redactsChineseAndSpacedFilesystemPaths() {
        String raw = "样本 /data/实验/样本.png 以及 /workspace/项目 一/结果 文件.json "
                + "Windows D:/研究 数据/样本 集/结果.csv";
        String sanitized = KnowledgeSanitizer.sanitize(raw);

        assertFalse(sanitized.contains("/data/实验"));
        assertFalse(sanitized.contains("/workspace/项目"));
        assertFalse(sanitized.contains("D:/研究"));
        assertTrue(sanitized.contains("[已过滤路径]"));
    }

    @Test
    void redactsUnknownAbsolutePathsAndChinesePaths() {
        String raw = "样本 /datasets/private/sample.png 以及 /训练数据/样本.png";
        String sanitized = KnowledgeSanitizer.sanitize(raw);

        assertFalse(sanitized.contains("/datasets"));
        assertFalse(sanitized.contains("/训练数据"));
        assertTrue(sanitized.contains("[已过滤路径]"));
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
