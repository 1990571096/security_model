package com.securitymodel.domain.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GTSRB is distributed by the VaB runtime as one HDF5 file. The file must stay inside the
 * VaB {@code dataset} directory: the upstream loader resolves it relative to the VaB
 * working directory instead of using the generic {@code --data_path} argument.
 */
public final class VabGtsrbDataset {

    public static final String FILE_NAME = "gtsrb_dataset.h5";
    public static final String PARAMETER_VALUE = "gtsrb";

    private static final byte[] HDF5_SIGNATURE = {
            (byte) 0x89, 0x48, 0x44, 0x46, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private final Path file;
    private final Path runtimeRoot;

    private VabGtsrbDataset(Path file, Path runtimeRoot) {
        this.file = file;
        this.runtimeRoot = runtimeRoot;
    }

    /**
     * Accepts the HDF5 file itself or a dedicated {@code gtsrb} directory. A generic
     * {@code dataset} directory is intentionally not classified as GTSRB because it may
     * contain several datasets and is already used as the shared CIFAR runtime root.
     */
    public static VabGtsrbDataset fromPath(Path path) {
        if (path == null) {
            return null;
        }
        if (Files.isRegularFile(path)
                && FILE_NAME.equals(path.getFileName().toString())) {
            return new VabGtsrbDataset(path, path.getParent());
        }
        if (Files.isDirectory(path)
                && "gtsrb".equals(path.getFileName().toString())) {
            Path candidate = path.resolve(FILE_NAME);
            if (Files.isRegularFile(candidate)) {
                return new VabGtsrbDataset(candidate, path);
            }
        }
        return null;
    }

    public Path file() {
        return file;
    }

    public Path runtimeRoot() {
        return runtimeRoot;
    }

    public long fileSize() throws IOException {
        return Files.size(file);
    }

    public boolean hasHdf5Signature() throws IOException {
        if (Files.size(file) < HDF5_SIGNATURE.length) {
            return false;
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] actual = input.readNBytes(HDF5_SIGNATURE.length);
            if (actual.length != HDF5_SIGNATURE.length) {
                return false;
            }
            for (int index = 0; index < HDF5_SIGNATURE.length; index++) {
                if (actual[index] != HDF5_SIGNATURE[index]) {
                    return false;
                }
            }
            return true;
        }
    }

    public Statistics statistics() throws IOException {
        // The official GTSRB HDF5 contains X_train/X_val for 39,209 training images,
        // a separate 12,630-image test split, 43 classes and 32x32 RGB inputs.
        return new Statistics(39_209, 12_630, 43, 32 * 32 * 3, Files.size(file));
    }

    public record Statistics(int trainingSamples, int testSamples, int classes, int features,
                             long fileSize) {}
}
