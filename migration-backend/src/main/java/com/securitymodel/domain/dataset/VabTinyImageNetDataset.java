package com.securitymodel.domain.dataset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tiny ImageNet-200 is recognized as a dataset resource so it can be registered and
 * inspected, but the current VaB LDD integration does not consume it yet.
 */
public final class VabTinyImageNetDataset {

    public static final String DIRECTORY_NAME = "tiny-imagenet-200";

    private final Path directory;

    private VabTinyImageNetDataset(Path directory) {
        this.directory = directory;
    }

    public static VabTinyImageNetDataset fromDirectory(Path path) {
        if (path == null || !Files.isDirectory(path)
                || !DIRECTORY_NAME.equals(path.getFileName().toString())) {
            return null;
        }
        return new VabTinyImageNetDataset(path);
    }

    public Path directory() {
        return directory;
    }

    public List<String> missingLayoutEntries() {
        return List.of("wnids.txt", "train", "val").stream()
                .filter(name -> {
                    Path candidate = directory.resolve(name);
                    return !Files.exists(candidate) || !Files.isReadable(candidate);
                })
                .toList();
    }

    public Statistics statistics() {
        // Tiny ImageNet-200: 100,000 training images, 200 classes, 64x64 RGB inputs.
        return new Statistics(100_000, 10_000, 200, 64 * 64 * 3);
    }

    public record Statistics(int trainingSamples, int validationSamples, int classes, int features) {}
}
