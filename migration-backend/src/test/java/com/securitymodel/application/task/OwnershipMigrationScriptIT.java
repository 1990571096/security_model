package com.securitymodel.application.task;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executes sql/security_model_ownership.sql against a throwaway MySQL schema so its
 * syntax, the operator switch and the two-pass backfill are proven by execution rather
 * than by reading. It runs only under the mysql-it profile.
 */
@SpringJUnitConfig(OwnershipMigrationScriptIT.MigrationTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnershipMigrationScriptIT {

    private static final String SCHEMA_PREFIX = "security_model_owner_it_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String HOST = env("MYSQL_IT_HOST", "localhost");
    private static final int PORT = Integer.parseInt(env("MYSQL_IT_PORT", "3306"));
    private static final String USERNAME = env("MYSQL_IT_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_IT_PASSWORD", "");
    private static final Path SCRIPT = Path.of("..", "sql", "security_model_ownership.sql");
    private static volatile boolean schemaCreated;

    static {
        createIsolatedSchema();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OwnershipMigrationScriptIT::schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void prepareLegacySchema() {
        jdbc.execute("DROP TABLE IF EXISTS sys_pre_defense");
        jdbc.execute("DROP TABLE IF EXISTS sys_user");
        jdbc.execute("""
                CREATE TABLE sys_user (
                  user_id BIGINT NOT NULL,
                  username VARCHAR(180) NULL,
                  nick_name VARCHAR(255) NULL,
                  password VARCHAR(255) NULL,
                  is_admin BIT(1) NULL,
                  enabled BIT(1) NULL,
                  PRIMARY KEY (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        // Same shape as eladmin.sql before R1: no owner_user_id / data_origin columns.
        jdbc.execute("""
                CREATE TABLE sys_pre_defense (
                  task_id BIGINT NOT NULL,
                  task_name VARCHAR(100) NOT NULL,
                  description TEXT NULL,
                  status VARCHAR(20) NOT NULL,
                  dataset_id BIGINT NOT NULL,
                  algorithm_id BIGINT NOT NULL,
                  cleaning_result JSON NULL,
                  hyper_id BIGINT NULL,
                  custom_params JSON NULL,
                  start_time DATETIME NULL,
                  end_time DATETIME NULL,
                  progress INT NULL DEFAULT 0,
                  output_dataset_id BIGINT NULL,
                  create_by VARCHAR(255) NULL,
                  update_by VARCHAR(255) NULL,
                  create_time DATETIME NULL,
                  update_time DATETIME NULL,
                  execution_snapshot JSON NULL,
                  current_run_id VARCHAR(64) NULL,
                  current_attempt INT NULL,
                  PRIMARY KEY (task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbc.update("INSERT INTO sys_user VALUES (1, 'admin', '管理员', 'x', b'1', b'1')");
        jdbc.update("INSERT INTO sys_user VALUES (2, 'alice', 'Alice', 'x', b'0', b'1')");

        // 1: real historical run (has frozen snapshot, terminal status).
        jdbc.update("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    cleaning_result, create_by, start_time, end_time, progress, execution_snapshot, current_run_id)
                VALUES (1, 'real-run', 'success', 1, 1, '{"executor":"python-vab-adapter"}', 'admin',
                    NOW(), NOW(), 100, '{"algorithmId":1}', 'run-1')
                """);
        // 2: real historical run authored by the mapped non-admin account.
        jdbc.update("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    cleaning_result, create_by, start_time, end_time, progress, execution_snapshot, current_run_id)
                VALUES (2, 'alice-run', 'failed', 1, 1, '{"executor":"python-vab-adapter"}', 'alice',
                    NOW(), NOW(), 100, '{"algorithmId":1}', 'run-2')
                """);
        // 3, 4: seeded "running" rows with no snapshot, no result and no run id (demo material).
        jdbc.update("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    progress, create_by, start_time, update_time)
                VALUES (3, 'MNIST数据预处理', 'running', 2, 1, 65, 'test', NOW(), NOW())
                """);
        jdbc.update("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    progress, create_by, start_time, update_time)
                VALUES (4, '文本数据清洗任务', 'running', 3, 1, 1, 'test', NOW(), NOW())
                """);
        // 5: author that does not exist in sys_user.
        jdbc.update("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    cleaning_result, create_by, execution_snapshot, current_run_id)
                VALUES (5, 'ghost-run', 'success', 1, 1, '{}', 'ghost', '{}', 'run-5')
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
    void schemaPassAddsColumnsWithoutTouchingAnyRow() throws Exception {
        runScript();

        assertTrue(columnExists("owner_user_id"));
        assertTrue(columnExists("data_origin"));
        assertTrue(columnExists("owner_backfill_status"));
        assertTrue(indexExists("idx_owner_status"));

        // Default operator switch is 0, so a review-only pass must not guess ownership.
        assertNull(ownerOf(1L));
        assertNull(originOf(1L));
        assertNull(backfillStatusOf(1L));
        assertNull(ownerOf(3L));
    }

    @Test
    void approvedDemoIdsAreIsolatedAndOtherRowsAreBackfilled() throws Exception {
        runScript();
        applyOperatorSwitches("1", "3,4", "");

        // Confirmed demo rows: no owner, excluded from user queries, still traceable.
        assertNull(ownerOf(3L));
        assertEquals("demo", originOf(3L));
        assertEquals("unattributed", backfillStatusOf(3L));
        assertNull(ownerOf(4L));
        assertEquals("demo", originOf(4L));

        // Real rows keep their author mapping.
        assertEquals(1L, ownerOf(1L));
        assertEquals("matched", backfillStatusOf(1L));
        assertEquals("legacy", originOf(1L));
        assertEquals(2L, ownerOf(2L));

        // Unknown author is isolated rather than guessed.
        assertNull(ownerOf(5L));
        assertEquals("unattributed", backfillStatusOf(5L));
        assertEquals("legacy", originOf(5L));
    }

    @Test
    void rejectedMatchBecomesUnattributed() throws Exception {
        runScript();
        applyOperatorSwitches("1", "3,4", "2");

        assertNull(ownerOf(2L));
        assertEquals("unattributed", backfillStatusOf(2L));
    }

    private void runScript() throws Exception {
        assertTrue(Files.exists(SCRIPT), "ownership script must exist at " + SCRIPT.toAbsolutePath());
        try (var connection = DriverManager.getConnection(schemaUrl(), USERNAME, PASSWORD)) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(SCRIPT));
        }
    }

    /** Mimics the operator running pass 2 on the same connection with the switches edited. */
    private void applyOperatorSwitches(String apply, String demoIds, String rejectedIds) throws Exception {
        try (var connection = DriverManager.getConnection(schemaUrl(), USERNAME, PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET @apply_backfill = " + apply);
                statement.execute("SET @demo_task_ids = '" + demoIds + "'");
                statement.execute("SET @reject_task_ids = '" + rejectedIds + "'");
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(SCRIPT));
            }
        }
    }

    private boolean columnExists(String column) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'sys_pre_defense' AND COLUMN_NAME = ?
                """, Integer.class, SCHEMA, column);
        return count != null && count > 0;
    }

    private boolean indexExists(String index) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'sys_pre_defense' AND INDEX_NAME = ?
                """, Integer.class, SCHEMA, index);
        return count != null && count > 0;
    }

    private Long ownerOf(long taskId) {
        return jdbc.queryForObject(
                "SELECT owner_user_id FROM sys_pre_defense WHERE task_id = ?", Long.class, taskId);
    }

    private String originOf(long taskId) {
        return jdbc.queryForObject(
                "SELECT data_origin FROM sys_pre_defense WHERE task_id = ?", String.class, taskId);
    }

    private String backfillStatusOf(long taskId) {
        return jdbc.queryForObject(
                "SELECT owner_backfill_status FROM sys_pre_defense WHERE task_id = ?", String.class, taskId);
    }

    private static void createIsolatedSchema() {
        try (var connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCHEMA + "` CHARACTER SET utf8mb4");
            schemaCreated = true;
        } catch (Exception exception) {
            throw new IllegalStateException("MySQL is required for the ownership script IT", exception);
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
