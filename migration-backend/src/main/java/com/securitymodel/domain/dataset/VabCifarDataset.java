package com.securitymodel.domain.dataset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** The two extracted directory layouts understood by the VaB runtime. */
public enum VabCifarDataset {
    // Published Python batches, identified by the same checksums torchvision verifies.
    CIFAR10("cifar10", "cifar-10-batches-py", 10, Map.of(
            "batches.meta", "5ff9c542aee3614f3951f8cda6e48888",
            "data_batch_1", "c99cafc152244af753f735de768cd75f",
            "data_batch_2", "d4bba439e000b95fd0a9bffe97cbabec",
            "data_batch_3", "54ebc095f3ab1f0389bbae665268c751",
            "data_batch_4", "634d18415352ddfa80567beed471001a",
            "data_batch_5", "482c414d41f54cd18b22e5b47cb7c3cb",
            "test_batch", "40351d587109b95175f43aff81a1287e")),
    CIFAR100("cifar100", "cifar-100-python", 100, Map.of(
            "meta", "7973b15100ade9c7d40fb424638fde48",
            "train", "16019d7e3df5f24257cddd939b257f8d",
            "test", "f0ef6b0ae62326f3e7ffdfab6717acfc"));

    private final String parameterValue;
    private final String directoryName;
    private final int classCount;
    private final Map<String, String> checksums;

    VabCifarDataset(String parameterValue, String directoryName, int classCount, Map<String, String> checksums) {
        this.parameterValue = parameterValue;
        this.directoryName = directoryName;
        this.classCount = classCount;
        this.checksums = checksums;
    }

    public String parameterValue() {
        return parameterValue;
    }

    public static VabCifarDataset fromDirectory(Path path) {
        return Arrays.stream(values())
                .filter(dataset -> Path.of(dataset.directoryName).equals(path.getFileName()))
                .findFirst()
                .orElse(null);
    }

    public List<String> unusableFiles(Path directory) {
        return checksums.keySet().stream().sorted()
                .filter(name -> {
                    Path file = directory.resolve(name);
                    return !Files.isRegularFile(file) || !Files.isReadable(file) || file.toFile().length() == 0;
                })
                .toList();
    }

    public Statistics verifyOfficialBatches(Path directory) throws IOException {
        long fileSize = 0;
        for (String name : checksums.keySet().stream().sorted().toList()) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
            try (var input = new DigestInputStream(Files.newInputStream(directory.resolve(name)), digest)) {
                fileSize += input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            if (!checksums.get(name).equals(HexFormat.of().formatHex(digest.digest()))) {
                throw new IOException("CIFAR 官方批次校验和不匹配：" + name);
            }
        }
        return new Statistics(50_000, 10_000, classCount, 32 * 32 * 3, fileSize);
    }

    public record Statistics(int trainingSamples, int testSamples, int classes, int features, long fileSize) {}
}
