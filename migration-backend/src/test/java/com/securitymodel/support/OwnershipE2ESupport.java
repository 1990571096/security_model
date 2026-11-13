package com.securitymodel.support;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

/**
 * Throwaway MySQL schema for the end-to-end ownership tests: real {@code sys_user} and
 * {@code sys_pre_defense} tables, real login credentials, two ordinary accounts plus one
 * administrator, and four task rows that cover the ownership states.
 *
 * <p>Everything lives in {@code security_model_e2e_<32 hex>} so the developer database is
 * never touched. The tests are skipped by surefire and run only under {@code -Pmysql-it}.</p>
 */
public final class OwnershipE2ESupport {

    public static final String SCHEMA = "security_model_e2e_" + UUID.randomUUID().toString().replace("-", "");
    public static final String HOST = env("MYSQL_IT_HOST", "localhost");
    public static final int PORT = Integer.parseInt(env("MYSQL_IT_PORT", "3306"));
    public static final String USERNAME = env("MYSQL_IT_USERNAME", "root");
    public static final String PASSWORD = env("MYSQL_IT_PASSWORD", "");
    public static final String RAW_PASSWORD = "pw";
    public static final String ENCODED_PASSWORD = new BCryptPasswordEncoder().encode(RAW_PASSWORD);

    public static final long ADMIN_ID = 1L;
    public static final long ALICE_ID = 2L;
    public static final long BOB_ID = 3L;
    public static final long ALICE_TASK = 100L;
    public static final long ALICE_DEMO_TASK = 200L;
    public static final long BOB_TASK = 300L;
    public static final long UNATTRIBUTED_TASK = 400L;

    static {
        createSchemaAndSeed();
        // Both end-to-end classes share one schema; drop it when the test JVM exits.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                dropSchema();
            } catch (Exception ignored) {
                // The schema is unique per run; a leftover only costs a manual DROP DATABASE.
            }
        }));
    }

    private OwnershipE2ESupport() {
    }

    public static String schemaUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA
                + "?useUnicode=true&characterEncoding=utf-8&useSSL=false"
                + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    }

    public static String adminUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT
                + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    }

    public static void setEnabled(long userId, boolean enabled) throws Exception {
        try (Connection connection = DriverManager.getConnection(schemaUrl(), USERNAME, PASSWORD);
             var statement = connection.prepareStatement(
                     "UPDATE sys_user SET enabled = ? WHERE user_id = ?")) {
            statement.setBoolean(1, enabled);
            statement.setLong(2, userId);
            statement.executeUpdate();
        }
    }

    public static void dropSchema() throws Exception {
        if (!SCHEMA.matches("security_model_e2e_[a-f0-9]{32}")) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + SCHEMA + "`");
        }
    }

    private static void createSchemaAndSeed() {
        try (Connection connection = DriverManager.getConnection(adminUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCHEMA + "` CHARACTER SET utf8mb4");
            statement.execute("USE `" + SCHEMA + "`");
            createUserTable(statement, SCHEMA);
            createTaskTable(statement);
            seedUsers(statement);
            seedTasks(statement);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "MySQL is required for the end-to-end ownership tests (set MYSQL_IT_PASSWORD)", exception);
        }
    }

    private static void createUserTable(Statement statement, String schema) throws Exception {
        statement.execute("""
                CREATE TABLE `sys_user` (
                  user_id BIGINT NOT NULL,
                  username VARCHAR(180) NULL,
                  nick_name VARCHAR(255) NULL,
                  password VARCHAR(255) NULL,
                  is_admin BIT(1) NULL DEFAULT b'0',
                  enabled BIT(1) NULL,
                  PRIMARY KEY (user_id),
                  UNIQUE KEY uniq_username (username)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private static void createTaskTable(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE `sys_pre_defense` (
                  task_id BIGINT NOT NULL,
                  task_name VARCHAR(100) NOT NULL,
                  description TEXT NULL,
                  status VARCHAR(20) NOT NULL DEFAULT 'pending',
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
                  owner_user_id BIGINT NULL,
                  data_origin VARCHAR(16) NULL,
                  owner_backfill_status VARCHAR(16) NULL,
                  PRIMARY KEY (task_id),
                  KEY idx_owner_status (owner_user_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private static void seedUsers(Statement statement) throws Exception {
        statement.execute("INSERT INTO sys_user VALUES (1, 'admin', '管理员', '" + ENCODED_PASSWORD
                + "', b'1', b'1')");
        statement.execute("INSERT INTO sys_user VALUES (2, 'alice', 'Alice', '" + ENCODED_PASSWORD
                + "', b'0', b'1')");
        statement.execute("INSERT INTO sys_user VALUES (3, 'bob', 'Bob', '" + ENCODED_PASSWORD
                + "', b'0', b'1')");
    }

    private static void seedTasks(Statement statement) throws Exception {
        // Alice's own real task.
        statement.execute("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    progress, create_by, create_time, owner_user_id, data_origin)
                VALUES (100, 'alice-real', 'success', 1, 1, 100, 'alice', NOW(), 2, 'user')
                """);
        // Demo row owned by Alice on paper: readable by nobody except a cross-user admin.
        statement.execute("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    progress, create_by, create_time, owner_user_id, data_origin, owner_backfill_status)
                VALUES (200, 'demo-row', 'success', 2, 1, 100, 'alice', NOW(), 2, 'demo', 'unattributed')
                """);
        // Bob's own pending task.
        statement.execute("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    progress, create_by, create_time, owner_user_id, data_origin)
                VALUES (300, 'bob-pending', 'pending', 1, 1, 0, 'bob', NOW(), 3, 'user')
                """);
        // Historical row that could not be attributed.
        statement.execute("""
                INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id,
                    progress, create_by, create_time, owner_user_id, data_origin, owner_backfill_status)
                VALUES (400, 'ghost-row', 'success', 1, 1, 100, 'ghost', NOW(), NULL, 'legacy', 'unattributed')
                """);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
