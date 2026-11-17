package com.securitymodel.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Executes the real VaB seed against a throwaway MySQL schema. The existing
 * {@link VabSeedPortabilityTest} only checks text; this IT proves the actual row
 * behaviour: no-root registration, idempotent rerun, pending dataset registration,
 * path changes not overwriting an existing row, and operator edits surviving a rerun.
 */
@SpringJUnitConfig(VabSeedMySqlIT.MysqlTestConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VabSeedMySqlIT {

    private static final String NAME = "VaB CIFAR-10 Runtime Root";
    private static final String ALGORITHM = "vab://pre-defense/ldd";
    private static final String TEMPLATE = "LDD (VaB) - CIFAR10 SIG";
    private static final String SCHEMA_PREFIX = "security_model_vabseed_it_";
    private static final String SCHEMA = SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    private static final String HOST = env("MYSQL_IT_HOST", "localhost");
    private static final int PORT = Integer.parseInt(env("MYSQL_IT_PORT", "3306"));
    private static final String USERNAME = env("MYSQL_IT_USERNAME", "root");
    private static final String PASSWORD = env("MYSQL_IT_PASSWORD", "");
    private static final Path BASE = Path.of("..", "sql", "eladmin.sql");
    private static final Path SEED = Path.of("..", "sql", "security_model_vab_seed.sql");
    private static final Path BASELINE = Path.of("..", "sql",
            "security_model_dataset_verification_baseline.sql");
    private static final Path LEGACY_TASK = Path.of("..", "sql",
            "security_model_legacy_running_task_baseline.sql");
    private static final Path RUN_CONTEXT = Path.of("..", "sql",
            "security_model_task_run_context.sql");
    private static volatile boolean schemaCreated;
    private static volatile boolean baseLoaded;

    static {
        createIsolatedSchema();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", VabSeedMySqlIT::schemaUrl);
        registry.add("spring.datasource.username", () -> USERNAME);
        registry.add("spring.datasource.password", () -> PASSWORD);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @BeforeEach
    void resetSeedRows() {
        ensureBaseSchema();
        // sql/eladmin.sql has several child tables referencing sys_algorithm/sys_hyper.
        // Disable FK checks inside one connection so the three seed-relevant tables start
        // empty without deleting unrelated historical rows.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                statement.execute("DELETE FROM sys_hyper");
                statement.execute("DELETE FROM sys_algorithm");
                statement.execute("DELETE FROM sys_dataset");
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            return null;
        });
    }

    @AfterAll
    static void dropSchema() {
        if (!schemaCreated) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(rootUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + SCHEMA + "`");
        } catch (Exception ignored) {
            // Best-effort cleanup; the random schema name cannot collide with a real schema.
        }
    }

    @Test
    void withoutRootRegistersAlgorithmAndTemplateButNoDataset() {
        executeSeed(null);

        assertEquals(0, count("SELECT COUNT(*) FROM sys_dataset WHERE dataset_name='" + NAME + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_algorithm WHERE implementation_path='" + ALGORITHM + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_hyper WHERE template_name='" + TEMPLATE + "'"));
        Long algorithmId = jdbc.queryForObject(
                "SELECT algorithm_id FROM sys_algorithm WHERE implementation_path=?", Long.class, ALGORITHM);
        Long templateId = jdbc.queryForObject(
                "SELECT template_id FROM sys_hyper WHERE template_name=?", Long.class, TEMPLATE);

        executeSeed(null);

        assertEquals(1, count("SELECT COUNT(*) FROM sys_algorithm WHERE implementation_path='" + ALGORITHM + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_hyper WHERE template_name='" + TEMPLATE + "'"));
        assertEquals(algorithmId, jdbc.queryForObject(
                "SELECT algorithm_id FROM sys_algorithm WHERE implementation_path=?", Long.class, ALGORITHM));
        assertEquals(templateId, jdbc.queryForObject(
                "SELECT template_id FROM sys_hyper WHERE template_name=?", Long.class, TEMPLATE));
    }

    @Test
    void datasetStartsPendingWithUnknownStatisticsAndPathChangeDoesNotOverwrite() {
        executeSeed("F:/tmp/vab-a");
        Map<String, Object> first = dataset();
        assertEquals("pending", first.get("parse_status"));
        assertNull(first.get("sample_count"));
        assertNull(first.get("feature_count"));
        assertNull(first.get("class_count"));
        assertNull(first.get("parse_time"));
        assertEquals("F:/tmp/vab-a", first.get("file_path"));
        String firstUpdateTime = String.valueOf(first.get("update_time"));

        executeSeed("F:/tmp/vab-b");
        Map<String, Object> second = dataset();
        assertEquals("pending", second.get("parse_status"));
        assertEquals("F:/tmp/vab-a", second.get("file_path"), "rerun must not replace an existing path");
        assertEquals(firstUpdateTime, String.valueOf(second.get("update_time")),
                "rerun must not touch update_time");
        assertNull(second.get("sample_count"));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_dataset WHERE dataset_name='" + NAME + "'"));
    }

    @Test
    void operatorEditsSurviveARerun() {
        executeSeed("F:/tmp/vab-seed");
        Long algorithmId = jdbc.queryForObject(
                "SELECT algorithm_id FROM sys_algorithm WHERE implementation_path=?", Long.class, ALGORITHM);
        Long templateId = jdbc.queryForObject(
                "SELECT template_id FROM sys_hyper WHERE template_name=?", Long.class, TEMPLATE);
        String algorithmUpdateTime = jdbc.queryForObject(
                "SELECT update_time FROM sys_algorithm WHERE algorithm_id=?", String.class, algorithmId);
        String templateUpdateTime = jdbc.queryForObject(
                "SELECT update_time FROM sys_hyper WHERE template_id=?", String.class, templateId);

        jdbc.update("UPDATE sys_algorithm SET enabled=b'0', defense_stage='post', "
                + "default_config=?, update_by='operator' WHERE algorithm_id=?",
                "{\"r\":0.5}", algorithmId);
        jdbc.update("UPDATE sys_hyper SET enabled=b'0', parameter_config=?, "
                + "description='operator edited', update_by='operator' WHERE template_id=?",
                "{\"r\":0.4}", templateId);
        jdbc.update("UPDATE sys_dataset SET file_path='F:/operator/path', parse_status='failed', "
                + "sample_count=12345, parse_message='operator edited' WHERE dataset_name=?", NAME);

        executeSeed("F:/tmp/vab-rerun");

        assertEquals(0, jdbc.queryForObject(
                "SELECT enabled+0 FROM sys_algorithm WHERE algorithm_id=?", Integer.class, algorithmId));
        assertEquals("post", jdbc.queryForObject(
                "SELECT defense_stage FROM sys_algorithm WHERE algorithm_id=?", String.class, algorithmId));
        assertEquals("0.5", jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(default_config, '$.r')) FROM sys_algorithm "
                        + "WHERE algorithm_id=?", String.class, algorithmId));
        assertEquals(algorithmUpdateTime, jdbc.queryForObject(
                "SELECT update_time FROM sys_algorithm WHERE algorithm_id=?", String.class, algorithmId));

        assertEquals(0, jdbc.queryForObject(
                "SELECT enabled+0 FROM sys_hyper WHERE template_id=?", Integer.class, templateId));
        assertEquals("0.4", jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(parameter_config, '$.r')) FROM sys_hyper "
                        + "WHERE template_id=?", String.class, templateId));
        assertEquals("operator edited", jdbc.queryForObject(
                "SELECT description FROM sys_hyper WHERE template_id=?", String.class, templateId));
        assertEquals(templateUpdateTime, jdbc.queryForObject(
                "SELECT update_time FROM sys_hyper WHERE template_id=?", String.class, templateId));

        Map<String, Object> dataset = dataset();
        assertEquals("F:/operator/path", dataset.get("file_path"));
        assertEquals("failed", dataset.get("parse_status"));
        assertEquals(12345, ((Number) dataset.get("sample_count")).intValue());
        assertEquals("operator edited", dataset.get("parse_message"));

        assertEquals(1, count("SELECT COUNT(*) FROM sys_algorithm WHERE implementation_path='" + ALGORITHM + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_hyper WHERE template_name='" + TEMPLATE + "'"));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_dataset WHERE dataset_name='" + NAME + "'"));
    }

    @Test
    void historicalSampleDatasetIsNotAvailableUntilRealVerification() {
        jdbc.update("INSERT INTO sys_dataset (dataset_name, dataset_type, file_path, "
                + "parse_status, parse_message, sample_count, feature_count, class_count, "
                + "enabled, create_by, create_time, update_time) "
                + "VALUES ('历史样例-CIFAR', 'image', '/uploads/datasets/cifar10_train.zip', "
                + "'success', '解析成功，发现50000个样本', 50000, 3072, 10, b'1', "
                + "'system', NOW(), NOW())");
        jdbc.update("INSERT INTO sys_dataset (dataset_name, dataset_type, file_path, "
                + "parse_status, parse_message, sample_count, feature_count, class_count, "
                + "enabled, create_by, create_time, update_time) "
                + "VALUES ('VaB历史目录登记', 'image', 'F:/VaB/dataset', "
                + "'success', 'Deployment-provided VaB runtime dataset root; the caller verified the path.', "
                + "50000, 3072, 10, b'1', 'system', NOW(), NOW())");
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT file_path, parse_status, parse_message FROM sys_dataset "
                        + "WHERE dataset_name='历史样例-CIFAR'");
        assertEquals("/uploads/datasets/cifar10_train.zip", before.get("file_path"));
        assertEquals("success", before.get("parse_status"));
        assertEquals("解析成功，发现50000个样本", before.get("parse_message"));
        executeSql(BASELINE);

        Map<String, Object> reset = jdbc.queryForMap(
                "SELECT parse_status, parse_message, sample_count, parse_time FROM sys_dataset "
                        + "WHERE dataset_name='历史样例-CIFAR'");
        assertEquals("pending", reset.get("parse_status"));
        assertEquals("历史样例数据：迁移版尚未验证，不作为真实可用资源；请管理员执行数据集验证",
                reset.get("parse_message"));
        assertNull(reset.get("sample_count"));
        assertNull(reset.get("parse_time"));

        Map<String, Object> vabReset = datasetByName("VaB历史目录登记");
        assertEquals("pending", vabReset.get("parse_status"));
        assertNull(vabReset.get("sample_count"));
        assertNull(vabReset.get("parse_time"));

        // A real verification changes the message, so a later baseline rerun must not undo it.
        jdbc.update("UPDATE sys_dataset SET parse_status='success', "
                + "parse_message='路径验证通过：存在且可读', sample_count=42, parse_time=NOW() "
                + "WHERE dataset_name='历史样例-CIFAR'");
        executeSql(BASELINE);

        Map<String, Object> verified = datasetByName("历史样例-CIFAR");
        assertEquals("success", verified.get("parse_status"));
        assertEquals("路径验证通过：存在且可读", verified.get("parse_message"));
        assertEquals(42, ((Number) verified.get("sample_count")).intValue());
    }

    @Test
    void legacyRunningRowsWithoutRunContextAreFailedButRealAttemptsAreUntouched() {
        // sys_pre_defense has FKs to both sys_dataset and sys_algorithm; the fixture rows
        // are created by this test, not borrowed from a real deployment.
        executeSeed(null);
        // sql/eladmin.sql is the pre-migration base; the run-context columns arrive with
        // the idempotent migration that the initializer applies before the legacy baseline.
        executeSql(RUN_CONTEXT);
        jdbc.update("INSERT INTO sys_dataset (dataset_name, enabled, dataset_type, file_path, "
                + "parse_status, create_time, update_time) "
                + "VALUES ('legacy-running-fixture', b'1', 'image', '/tmp/fixture', "
                + "'pending', NOW(), NOW())");
        Long datasetId = jdbc.queryForObject(
                "SELECT dataset_id FROM sys_dataset WHERE dataset_name='legacy-running-fixture'",
                Long.class);
        Long algorithmId = jdbc.queryForObject(
                "SELECT algorithm_id FROM sys_algorithm WHERE implementation_path='vab://pre-defense/ldd'",
                Long.class);

        String legacyName = "legacy-running-" + UUID.randomUUID();
        String realName = "real-running-" + UUID.randomUUID();
        jdbc.update("INSERT INTO sys_pre_defense (task_name, status, dataset_id, algorithm_id, "
                + "progress, create_time, update_time) VALUES (?, 'running', ?, ?, 65, NOW(), NOW())",
                legacyName, datasetId, algorithmId);
        jdbc.update("INSERT INTO sys_pre_defense (task_name, status, dataset_id, algorithm_id, "
                + "progress, current_run_id, current_attempt, create_time, update_time) "
                + "VALUES (?, 'running', ?, ?, 10, 'run-real', 1, NOW(), NOW())",
                realName, datasetId, algorithmId);
        executeSql(LEGACY_TASK);

        Map<String, Object> legacy = jdbc.queryForMap(
                "SELECT status, progress, cleaning_result FROM sys_pre_defense WHERE task_name=?",
                legacyName);
        assertEquals("failed", legacy.get("status"));
        assertEquals(0, ((Number) legacy.get("progress")).intValue());
        String result = String.valueOf(legacy.get("cleaning_result"));
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("EXECUTOR_RESTARTED"));
        org.junit.jupiter.api.Assertions.assertTrue(result.contains("应用重启导致任务中断"));

        Map<String, Object> real = jdbc.queryForMap(
                "SELECT status, progress, current_run_id FROM sys_pre_defense WHERE task_name=?",
                realName);
        assertEquals("running", real.get("status"));
        assertEquals(10, ((Number) real.get("progress")).intValue());
        assertEquals("run-real", real.get("current_run_id"));
    }

    private Map<String, Object> dataset() {
        return jdbc.queryForMap("SELECT file_path, parse_status, parse_message, sample_count, "
                + "feature_count, class_count, parse_time, update_time FROM sys_dataset "
                + "WHERE dataset_name=?", NAME);
    }

    private Map<String, Object> datasetByName(String name) {
        return jdbc.queryForMap("SELECT parse_status, parse_message, sample_count, parse_time "
                + "FROM sys_dataset WHERE dataset_name=?", name);
    }

    private void executeSql(Path path) {
        try (Connection connection = DriverManager.getConnection(schemaUrl(), USERNAME, PASSWORD)) {
            ScriptUtils.executeSqlScript(connection, utf8(path));
        } catch (Exception exception) {
            throw new AssertionError("SQL execution failed: " + path, exception);
        }
    }

    private static EncodedResource utf8(Path path) {
        return new EncodedResource(new FileSystemResource(path), StandardCharsets.UTF_8);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void executeSeed(String root) {
        try (Connection connection = DriverManager.getConnection(schemaUrl(), USERNAME, PASSWORD)) {
            try (Statement statement = connection.createStatement()) {
                if (root == null) {
                    statement.execute("SET @vab_dataset_root = NULL");
                } else {
                    statement.execute("SET @vab_dataset_root = '" + root.replace("'", "''") + "'");
                }
            }
            ScriptUtils.executeSqlScript(connection, utf8(SEED));
        } catch (Exception exception) {
            throw new AssertionError("seed execution failed", exception);
        }
    }

    private void ensureBaseSchema() {
        if (baseLoaded) {
            return;
        }
        synchronized (VabSeedMySqlIT.class) {
            if (baseLoaded) {
                return;
            }
            try (Connection connection = DriverManager.getConnection(schemaUrl(), USERNAME, PASSWORD)) {
            ScriptUtils.executeSqlScript(connection, utf8(BASE));
                baseLoaded = true;
            } catch (Exception exception) {
                throw new AssertionError("base schema execution failed", exception);
            }
        }
    }

    private static void createIsolatedSchema() {
        try (Connection connection = DriverManager.getConnection(rootUrl(), USERNAME, PASSWORD);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + SCHEMA
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            schemaCreated = true;
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static String schemaUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&characterEncoding=utf8";
    }

    private static String rootUrl() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/"
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
                + "&characterEncoding=utf8";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @TestConfiguration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
    static class MysqlTestConfiguration {
    }
}
