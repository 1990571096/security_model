package me.zhengjie.modules.system.service.impl;

import com.alibaba.fastjson2.JSONArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import me.zhengjie.exception.BadRequestException;

class CleanSubsetExportServiceImplTest {

    private final CleanSubsetExportServiceImpl service = new CleanSubsetExportServiceImpl();

    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyAcceptsSelectionFilesInsideTheRealArtifactDirectory() throws IOException {
        Path artifactDirectory = Files.createDirectory(temporaryDirectory.resolve("artifacts"));
        Path localSelection = Files.write(
                artifactDirectory.resolve("selected_indices_5.npy"), new byte[]{1});
        Path externalSelection = Files.write(
                temporaryDirectory.resolve("selected_indices_10.npy"), new byte[]{2});
        Path linkedSelection = artifactDirectory.resolve("selected_indices_10.npy");

        try {
            Files.createSymbolicLink(linkedSelection, externalSelection);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "当前文件系统不支持创建符号链接");
        }

        JSONArray rawFiles = new JSONArray();
        rawFiles.add(localSelection.toString());
        rawFiles.add(linkedSelection.toString());

        List<Path> selections = service.selectionFiles(rawFiles, artifactDirectory);

        assertEquals(1, selections.size());
        assertEquals(localSelection.toRealPath(), selections.get(0));
        assertTrue(selections.get(0).startsWith(artifactDirectory.toRealPath()));
    }

    @Test
    void convertsInvalidDirectoryReferencesToBadRequest() {
        assertThrows(BadRequestException.class,
                () -> service.safeDirectory("invalid\u0000path", "目录不可用"));
    }
}
