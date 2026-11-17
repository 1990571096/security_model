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
    private static final Path INITIALIZER = Path.of("..", "scripts", "dev",
            "InitializeSecurityModelDatabase.ps1");
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
    }

    @Test
    void legacyRunningBaselineIsNarrowAndNeverMarksSuccess() throws Exception {
        String sql = Files.readString(LEGACY_TASK);
        assertTrue(sql.contains("status` = 'failed'"), "legacy rows must converge to failed");
        assertTrue(sql.contains("current_run_id` IS NULL")
                        && sql.contains("current_attempt` IS NULL"),
                "only rows without execution context may be touched");
        assertFalse(sql.contains("'success'"),
                "the baseline must never fabricate a successful task");
    }
}
