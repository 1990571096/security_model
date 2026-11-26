package com.securitymodel.domain.dataset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VabDatasetFormatTest {

    @Test
    void recognizesGtsrbHdf5FileAndUsesParentAsRuntimeRoot(@TempDir Path root) throws Exception {
        Path datasetDirectory = Files.createDirectory(root.resolve("dataset"));
        Path h5 = datasetDirectory.resolve(VabGtsrbDataset.FILE_NAME);
        Files.write(h5, hdf5Prefix());

        VabGtsrbDataset dataset = VabGtsrbDataset.fromPath(h5);

        assertNotNull(dataset);
        assertEquals(datasetDirectory, dataset.runtimeRoot());
        assertTrue(dataset.hasHdf5Signature());
        assertEquals(39_209, dataset.statistics().trainingSamples());
        assertEquals(12_630, dataset.statistics().testSamples());
        assertEquals(43, dataset.statistics().classes());
    }

    @Test
    void rejectsFileWithWrongGtsrbSignature(@TempDir Path root) throws Exception {
        Path h5 = root.resolve(VabGtsrbDataset.FILE_NAME);
        Files.writeString(h5, "not-an-hdf5-file");

        VabGtsrbDataset dataset = VabGtsrbDataset.fromPath(h5);

        assertNotNull(dataset);
        assertFalse(dataset.hasHdf5Signature());
    }

    @Test
    void doesNotTreatSharedDatasetRootAsGtsrb(@TempDir Path root) throws Exception {
        Path datasetDirectory = Files.createDirectory(root.resolve("dataset"));
        Files.write(datasetDirectory.resolve(VabGtsrbDataset.FILE_NAME), hdf5Prefix());

        assertNull(VabGtsrbDataset.fromPath(datasetDirectory));
    }

    @Test
    void recognizesCompleteTinyImageNetLayout(@TempDir Path root) throws Exception {
        Path tiny = Files.createDirectory(root.resolve(VabTinyImageNetDataset.DIRECTORY_NAME));
        Files.writeString(tiny.resolve("wnids.txt"), "n01443537\n");
        Files.createDirectory(tiny.resolve("train"));
        Files.createDirectory(tiny.resolve("val"));

        VabTinyImageNetDataset dataset = VabTinyImageNetDataset.fromDirectory(tiny);

        assertNotNull(dataset);
        assertTrue(dataset.missingLayoutEntries().isEmpty());
    }

    @Test
    void reportsMissingTinyImageNetEntries(@TempDir Path root) throws Exception {
        Path tiny = Files.createDirectory(root.resolve(VabTinyImageNetDataset.DIRECTORY_NAME));
        Files.writeString(tiny.resolve("wnids.txt"), "n01443537\n");

        VabTinyImageNetDataset dataset = VabTinyImageNetDataset.fromDirectory(tiny);

        assertNotNull(dataset);
        assertTrue(dataset.missingLayoutEntries().contains("train"));
        assertTrue(dataset.missingLayoutEntries().contains("val"));
    }

    private static byte[] hdf5Prefix() {
        return new byte[]{(byte) 0x89, 0x48, 0x44, 0x46, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    }
}
