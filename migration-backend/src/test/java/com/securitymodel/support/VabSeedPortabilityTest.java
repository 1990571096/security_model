package com.securitymodel.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the VaB seed contract:
 * <ul>
 *   <li>no developer-machine path is embedded;</li>
 *   <li>a rerun is additive and cannot overwrite operator-owned configuration;</li>
 *   <li>a directory check is not a sample parse, so the dataset starts {@code pending};</li>
 *   <li>the initializer refuses any non-empty schema without a trusted marker.</li>
 * </ul>
 *
 * <p>The executable MySQL behaviour is covered by {@code VabSeedMySqlIT}; this test is
 * the fast textual regression guard that runs in the default unit-test phase.</p>
 */
class VabSeedPortabilityTest {

    private static final Path SEED = Path.of("..", "sql", "security_model_vab_seed.sql");
    private static final Path MVP_SEED = Path.of("..", "sql", "security_model_mvp_seed.sql");
    private static final Path MVP_CONVERGENCE = Path.of("..", "sql",
            "security_model_mvp_legacy_convergence.sql");
    private static final Path INITIALIZER = Path.of("..", "scripts", "dev",
            "InitializeSecurityModelDatabase.ps1");
    private static final Path GUARD_TEST = Path.of("..", "scripts", "dev",
            "Test-InitializeSecurityModelDatabaseGuard.ps1");
    private static final Path LEGACY_TASK = Path.of("..", "sql",
            "security_model_legacy_running_task_baseline.sql");
    private static final Pattern DRIVE_PATH =
            Pattern.compile("(?i)(?<![a-z0-9_])[a-z]:[\\\\/]");

    @Test
    void seedTakesDatasetRootFromCallerAndContainsNoMachinePath() throws Exception {
        String sql = Files.readString(SEED);
        assertTrue(sql.contains("@vab_dataset_root"), "seed must read the caller-provided root");
        assertTrue(sql.contains("SET @vab_root"), "seed must normalise the root variable");
        assertFalse(DRIVE_PATH.matcher(sql).find(), "seed must not embed a drive-letter path");
        assertFalse(sql.contains("F:/Study"), "seed must not embed the developer path");

        // The dataset must be skipped when no root is supplied, and a rerun must never
        // overwrite a path/status/statistics that an operator already owns.
        assertTrue(sql.contains("WHERE @vab_root IS NOT NULL"), "dataset insert must be guarded");
        assertFalse(sql.contains("UPDATE `sys_dataset`"), "rerun must not update the dataset row");
        assertFalse(sql.contains("UPDATE `sys_algorithm`"), "rerun must not update algorithms");
        assertFalse(sql.contains("UPDATE `sys_hyper`"), "rerun must not update templates");
        assertFalse(sql.contains("@vab_needs_update"), "there is no conditional dataset update");
        assertTrue(sql.contains("'pending'"), "new dataset registration must start pending");
        assertFalse(sql.contains("50000, 3072, 10, 'success'"),
                "seed must not claim parsed sample statistics");
    }

    @Test
    void seedStillCarriesTheRealLddConfiguration() throws Exception {
        String sql = Files.readString(SEED);
        assertTrue(sql.contains("vab://pre-defense/ldd"), "LDD implementation path is required");
        assertTrue(sql.contains("'{\"dataset\":\"cifar10\""), "default config must stay real");
        assertTrue(sql.contains("'{\"dataset\":{\"type\":\"select\""), "config schema must stay real");
    }

    @Test
    void initializerReadsRootAndVerifiesItBeforeWriting() throws Exception {
        String script = Files.readString(INITIALIZER);
        assertTrue(script.contains("SECURITY_MODEL_VAB_DATASET_ROOT"),
                "initializer must read the root from the environment");
        assertTrue(script.contains("-PathType Container"),
                "initializer must verify the root is a directory");
        assertTrue(script.contains("SET @vab_dataset_root ="),
                "initializer must inject the root as a SQL variable");
        assertTrue(script.contains("security_model_vab_seed.sql"),
                "initializer must apply the portable seed");
        assertTrue(script.contains("cifar-10-batches-py"),
                "initializer must check the CIFAR-10 directory structure");
        assertTrue(script.contains("data_batch_1") && script.contains("test_batch"),
                "initializer must check the CIFAR-10 batch files");
    }

    @Test
    void initializerRefusesAnyNonEmptySchemaWithoutTrustedMarker() throws Exception {
        String script = Files.readString(INITIALIZER);
        assertTrue(script.contains("information_schema.tables WHERE table_schema='$SchemaName'"),
                "guard must count all tables/views in the target schema");
        assertTrue(script.contains("existingTableCount"),
                "guard must use the total table count, not one business table");
        assertFalse(script.contains("table_name='sys_pre_defense'"),
                "sys_pre_defense alone is not a safe emptiness check");
        assertTrue(script.contains("没有可信初始化标记"),
                "guard must explain why the schema is refused");
        assertTrue(script.contains("security_model_legacy_running_task_baseline.sql"),
                "initializer must converge legacy running demo rows before startup recovery");
        assertTrue(script.contains("DbHost") && script.contains("Port"),
                "initializer must accept the same MySQL host/port as the guard test");
        assertTrue(script.contains("unclassified running tasks without run context"),
                "initializer must print an audit list instead of silently touching unknown rows");
    }

    @Test
    void mvpSeedIsAdditiveAndHistoricalChangesAreExplicit() throws Exception {
        String seed = Files.readString(MVP_SEED);
        assertFalse(seed.contains("UPDATE `sys_algorithm`"),
                "the normal MVP seed must not rewrite algorithm enabled flags");
        assertFalse(seed.contains("UPDATE `sys_menu`"),
                "the normal MVP seed must not rewrite menu visibility");
        assertFalse(seed.contains("SET `status` = 'deprecated'"),
                "the normal MVP seed must not deprecate model rows");
        assertTrue(seed.contains("builtin://baseline/statistical-cleaning"),
                "builtin catalog rows must still be registered");
        assertTrue(seed.contains("security_model_mvp_legacy_convergence.sql"),
                "the seed must point to the explicit historical migration");

        String migration = Files.readString(MVP_CONVERGENCE);
        assertTrue(migration.contains("NOT RUN BY scripts/dev/InitializeSecurityModelDatabase.ps1"),
                "the historical convergence must not be automatic");
        assertTrue(migration.contains("UPDATE `sys_algorithm`"),
                "the explicit migration owns the historical disable operation");
        assertTrue(migration.contains("UPDATE `sys_menu`"),
                "the explicit migration owns the historical menu operation");
    }

    @Test
    void legacyRunningBaselineIsNarrowAndNeverMarksSuccess() throws Exception {
        String sql = Files.readString(LEGACY_TASK);
        assertTrue(sql.contains("status` = 'failed'"), "legacy rows must converge to failed");
        assertTrue(sql.contains("current_run_id` IS NULL")
                        && sql.contains("current_attempt` IS NULL"),
                "only rows without execution context may be touched");
        assertTrue(sql.contains("task_id` = 3") && sql.contains("task_id` = 4"),
                "only the exact seed task IDs may be converged");
        assertTrue(sql.contains("MNIST数据预处理") && sql.contains("文本数据清洗任务"),
                "seed names are part of the stable identity");
        assertTrue(sql.contains("create_by` = 'test'"),
                "seed author is part of the stable identity");
        assertFalse(sql.contains("可安全重试"),
                "the baseline must not claim retry is safe without evidence");
        assertFalse(sql.contains("'success'"),
                "the baseline must never fabricate a successful task");

        String guardTest = Files.readString(GUARD_TEST);
        assertTrue(guardTest.contains("DbHost") && guardTest.contains("Port"),
                "guard test must pass host/port to the real initializer");
        assertTrue(guardTest.contains("sys_sentinel") && guardTest.contains("beforeTables"),
                "guard test must assert sentinel rows and table count are unchanged");
    }
}
