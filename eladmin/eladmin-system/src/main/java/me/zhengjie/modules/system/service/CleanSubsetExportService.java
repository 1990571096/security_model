package me.zhengjie.modules.system.service;

import me.zhengjie.modules.system.domain.PreDefense;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Exports the raw clean dataset subset reconstructed from a defense algorithm's selected indices.
 */
public interface CleanSubsetExportService {

    Path export(PreDefense task, Integer isolationRatio) throws IOException;

    Map<String, Object> describe(PreDefense task);

    String buildDownloadFileName(PreDefense task, Integer isolationRatio);
}
