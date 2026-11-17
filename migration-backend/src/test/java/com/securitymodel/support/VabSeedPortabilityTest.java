package com.securitymodel.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the VaB seed against re-introducing a developer-machine absolute path.
 *
 * <p>The seed is shipped to other deployments and to CI, so a concrete
 * {@code F:/...} path would make it either wrong or unusable. The dataset root must
 * come from the caller, and the initializer must verify it exists before marking the
 * dataset parsed.</p>
 */
class VabSeedPortabilityTest {

    private static final Path SEED = Path.of("..", "sql", "security_model_vab_seed.sql");
    private static final Path INITIALIZER = Path.of("..", "scripts", "dev",
            "InitializeSecurityModelDatabase.ps1");
    private static final Pattern DRIVE_PATH =
            Pattern.compile("(?i)(?<![a-z0-9_])[a-z]:[\\\\/]");

    @Test
    void seedTakesDatasetRootFromCallerAndContainsNoMachinePath() throws Exception {
        String sql = Files.readString(SEED);
        assertTrue(sql.contains("@vab_dataset_root"), "seed must read the caller-provided root");
        assertTrue(sql.contains("SET @vab_root"), "seed must normalise the root variable");
        assertFalse(DRIVE_PATH.matcher(sql).find(), "seed must not embed a drive-letter path");
        assertFalse(sql.contains("F:/Study"), "seed must not embed the developer path");

        // The dataset must be skipped when no root is supplied, and a rerun without a
        // root must not overwrite a path configured by a previous deployment.
        assertTrue(sql.contains("WHERE @vab_root IS NOT NULL"), "dataset insert must be guarded");
        assertTrue(sql.contains("AND @vab_root IS NOT NULL"), "dataset update must be guarded");
        assertTrue(sql.contains("@vab_needs_update"), "rerun must only write when the path changes");
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
    }
}
