package com.securitymodel.application.knowledge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes the R6 vector-cleanup up/down scripts against a throwaway MySQL schema:
 * new-schema shape, idempotent re-run, rollback, and re-apply. No knowledge rows other
 * than one retired fixture are modified.
 */
@SpringJUnitConfig(KnowledgeVectorCleanupMigrationIT.MigrationTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KnowledgeVectorCleanupMigrationIT {

    private static final String SCHEMA_PREFIX = "security_model_kv_mig_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String HOST = env("MYSQL_IT_HOST", "localhost");
    private static final int PORT = Integer.parseInt(env("MYSQL_IT_PORT", "3306"));
    private static final String USERNAME = env("MYSQL_IT_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_IT_PASSWORD", "");
    private static final Path UPGRADE = Path.of("..", "sql", "security_model_knowledge_vector_reconcile.sql");
    private static final Path ROLLBACK = Path.of("..", "sql",
            "security_model_knowledge_vector_reconcile_rollback.sql");
    private static volatile boolean schemaCreated;

    static {
        createIsolatedSchema();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", KnowledgeVectorCleanupMigrationIT::schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void prepareLegacyKnowledgeSchema() throws Exception {
        jdbc.execute("DROP TABLE IF EXISTS sys_knowledge_document");
        jdbc.execute("""
                CREATE TABLE sys_knowledge_document (
                  document_id BIGINT NOT NULL AUTO_INCREMENT,
                  source_type VARCHAR(32) NOT NULL,
                  source_key VARCHAR(256) NOT NULL,
                  version INT NOT NULL,
                  title VARCHAR(255) NOT NULL,
                  visibility VARCHAR(16) NOT NULL,
                  owner_user_id BIGINT NULL,
                  content_hash CHAR(64) NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  created_at DATETIME(3) NOT NULL,
                  updated_at DATETIME(3) NOT NULL,
                  PRIMARY KEY (document_id),
                  UNIQUE KEY uk_knowledge_document_source_version (source_key, version),
                  KEY idx_knowledge_document_active (source_key, status),
                  KEY idx_knowledge_document_acl (visibility, owner_user_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("""
                INSERT INTO sys_knowledge_document
                  (source_type, source_key, version, title, visibility, content_hash, status,
                   created_at, updated_at)
                VALUES ('task_report', 'task_report:migration', 1, 'migration', 'private', 'h',
                        'retired', NOW(3), NOW(3))
                """);
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (!schemaCreated || !SCHEMA.matches(SCHEMA_PREFIX + "[a-f0-9]{32}")) {
            return;
        }
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + SCHEMA + "`");
        }
    }

    @Test
    void upgradeIsRepeatableBackfillsRetiredRowsAndRollsBackCleanly() throws Exception {
        run(UPGRADE);
        run(UPGRADE);
        assertTrue(columnExists("vector_cleanup_status"));
        assertTrue(columnExists("vector_cleanup_attempts"));
        assertTrue(columnExists("vector_cleanup_next_at"));
        assertTrue(columnExists("vector_cleanup_updated_at"));
        assertTrue(indexExists("idx_knowledge_document_vector_cleanup"));
        assertEquals("pending", jdbc.queryForObject(
                "SELECT vector_cleanup_status FROM sys_knowledge_document", String.class));
        assertTrue(jdbc.queryForObject(
                "SELECT vector_cleanup_next_at FROM sys_knowledge_document", LocalDateTime.class) != null);

        run(ROLLBACK);
        run(ROLLBACK);
        assertFalse(columnExists("vector_cleanup_status"));
        assertFalse(indexExists("idx_knowledge_document_vector_cleanup"));

        run(UPGRADE);
        assertTrue(columnExists("vector_cleanup_status"));
    }

    private void run(Path script) throws Exception {
        assertTrue(Files.exists(script), "script must exist at " + script.toAbsolutePath());
        try (var connection = DriverManager.getConnection(schemaUrl(), USERNAME, PASSWORD)) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(script));
        }
    }

    private boolean columnExists(String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'sys_knowledge_document' AND COLUMN_NAME = ?
                """, Integer.class, SCHEMA, column);
        return count != null && count > 0;
    }

    private boolean indexExists(String index) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'sys_knowledge_document' AND INDEX_NAME = ?
                """, Integer.class, SCHEMA, index);
        return count != null && count > 0;
    }

    private static void createIsolatedSchema() {
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCHEMA + "` CHARACTER SET utf8mb4");
            schemaCreated = true;
        } catch (Exception exception) {
            throw new IllegalStateException("MySQL is required for the knowledge cleanup migration IT", exception);
        }
    }

    private static String schemaUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    private static String adminUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @TestConfiguration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
    static class MigrationTestConfiguration {
    }
}
