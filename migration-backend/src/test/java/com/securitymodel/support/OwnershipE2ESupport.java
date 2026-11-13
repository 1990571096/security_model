package com.securitymodel.support;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Throwaway MySQL schema for the end-to-end ownership tests: real {@code sys_user} and
 * {@code sys_pre_defense} tables, real login credentials, two ordinary accounts plus one
 * administrator, and four task rows that cover the ownership states.
 *
 * <p>Everything lives in {@code security_model_e2e_<32 hex>} so the developer database is
 * never touched. The tests are skipped by surefire and run only under {@code -Pmysql-it}.</p>
 */
public final class OwnershipE2ESupport {

    /** 1x1 transparent PNG, enough for a real artifact-preview response. */
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

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

    /** Task with a real artifact directory, a selection index and a finished export. */
    public static final long ARTIFACT_TASK = 500L;
    public static final long EXPORT_ID = 600L;
    public static final int EXPORT_RATIO = 10;

    public static final Path ARTIFACT_DIR = createArtifactDir();
    public static final Path PREVIEW_PNG = ARTIFACT_DIR.resolve("preview.png");
    public static final Path SELECTION_FILE = ARTIFACT_DIR.resolve("selected_indices_" + EXPORT_RATIO + ".npy");
    public static final Path EXPORT_ZIP = ARTIFACT_DIR.resolve("clean-subset-" + EXPORT_RATIO + ".zip");

    static {
        createSchemaAndSeed();
        // Both end-to-end classes share one schema; drop it when the test JVM exits.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                dropSchema();
            } catch (Exception ignored) {
                // The schema is unique per run; a leftover only costs a manual DROP DATABASE.
            }
            deleteRecursively(ARTIFACT_DIR);
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
            createExportTable(statement);
            createAuditTable(statement);
            createLeaseTable(statement);
            seedUsers(statement);
            seedTasks(statement);
            seedExport(statement);
            seedAudit(statement);
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

    private static void createExportTable(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE `sys_pre_defense_export` (
                  export_id BIGINT NOT NULL AUTO_INCREMENT,
                  task_id BIGINT NOT NULL,
                  isolation_ratio INT NOT NULL,
                  status VARCHAR(20) NOT NULL DEFAULT 'pending',
                  progress INT NULL DEFAULT 0,
                  file_path VARCHAR(512) NULL,
                  metadata_path VARCHAR(512) NULL,
                  error_code VARCHAR(64) NULL,
                  failure_reason VARCHAR(512) NULL,
                  start_time DATETIME NULL,
                  end_time DATETIME NULL,
                  create_by VARCHAR(255) NULL,
                  update_by VARCHAR(255) NULL,
                  create_time DATETIME NULL,
                  update_time DATETIME NULL,
                  PRIMARY KEY (export_id),
                  UNIQUE KEY uniq_task_ratio (task_id, isolation_ratio)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private static void createAuditTable(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE `security_operation_audit` (
                  audit_id BIGINT NOT NULL AUTO_INCREMENT,
                  resource_type VARCHAR(64) NOT NULL,
                  resource_id BIGINT NOT NULL,
                  action VARCHAR(64) NOT NULL,
                  operator VARCHAR(64) NULL,
                  before_status VARCHAR(32) NULL,
                  after_status VARCHAR(32) NULL,
                  request_snapshot LONGTEXT NULL,
                  result_message VARCHAR(512) NULL,
                  created_time DATETIME NULL,
                  PRIMARY KEY (audit_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
    }

    private static void createLeaseTable(Statement statement) throws Exception {
        statement.execute("""
                CREATE TABLE `sys_gpu_lease` (
                  slot_id VARCHAR(32) NOT NULL,
                  lease_token CHAR(36) NULL,
                  task_id BIGINT NULL,
                  run_id VARCHAR(64) NULL,
                  attempt INT NULL,
                  fencing_token BIGINT NOT NULL DEFAULT 0,
                  acquired_at DATETIME(3) NULL,
                  heartbeat_at DATETIME(3) NULL,
                  expires_at DATETIME(3) NULL,
                  released_at DATETIME(3) NULL,
                  PRIMARY KEY (slot_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        statement.execute("INSERT IGNORE INTO sys_gpu_lease (slot_id, fencing_token) VALUES ('default-gpu-0', 0)");
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
        // Alice's finished LDD task with a real artifact directory and selection index.
        String base = ARTIFACT_DIR.toAbsolutePath().toString().replace('\\', '/');
        String result = "{\"algorithm\":\"vab://pre-defense/ldd\","
                + "\"artifacts\":{\"path\":\"" + base + "\","
                + "\"selection_kind\":\"retained_clean_subset\","
                + "\"selection_files\":[\"" + base + "/selected_indices_" + EXPORT_RATIO + ".npy\"]},"
                + "\"effective_config\":{\"dataset\":\"cifar10\",\"data_path\":\"" + base + "\"}}";
        statement.execute("INSERT INTO sys_pre_defense (task_id, task_name, status, dataset_id, algorithm_id, "
                + "cleaning_result, progress, create_by, create_time, owner_user_id, data_origin) "
                + "VALUES (500, 'alice-ldd-artifacts', 'success', 4, 14, '" + result.replace("'", "''")
                + "', 100, 'alice', NOW(), 2, 'user')");
    }

    private static void seedExport(Statement statement) throws Exception {
        String zip = EXPORT_ZIP.toAbsolutePath().toString().replace('\\', '/');
        statement.execute("INSERT INTO sys_pre_defense_export (export_id, task_id, isolation_ratio, status, "
                + "progress, file_path, create_by, start_time, end_time, create_time, update_time) "
                + "VALUES (600, 500, " + EXPORT_RATIO + ", 'success', 100, '" + zip.replace("'", "''")
                + "', 'alice', NOW(), NOW(), NOW(), NOW())");
    }

    private static void seedAudit(Statement statement) throws Exception {
        statement.execute("""
                INSERT INTO security_operation_audit
                    (resource_type, resource_id, action, operator, before_status, after_status,
                     request_snapshot, result_message, created_time)
                VALUES ('pre_defense_task', 500, 'TASK_START', 'alice', 'pending', 'success',
                        '{"taskId":500}', '启动任务', NOW())
                """);
    }

    private static Path createArtifactDir() {
        try {
            Path dir = Files.createTempDirectory("security-model-e2e-artifacts-");
            Files.write(dir.resolve("preview.png"), Base64.getDecoder().decode(PNG_BASE64));
            Files.createFile(dir.resolve("selected_indices_" + EXPORT_RATIO + ".npy"));
            try (ZipOutputStream zip = new ZipOutputStream(
                    Files.newOutputStream(dir.resolve("clean-subset-" + EXPORT_RATIO + ".zip")))) {
                zip.putNextEntry(new ZipEntry("subset-index.txt"));
                zip.write("e2e".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return dir;
        } catch (IOException exception) {
            throw new IllegalStateException("cannot create the end-to-end artifact directory", exception);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup of a temp directory
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup of a temp directory
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
