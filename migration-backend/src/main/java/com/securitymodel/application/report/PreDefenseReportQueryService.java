package com.securitymodel.application.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.resource.ResourceQueryService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.hyper.Hyper;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PreDefenseReportQueryService {

    private final PreDefenseTaskMapper taskMapper;
    private final ResourceQueryService resourceQueryService;
    private final CleanSubsetExportService exportService;
    private final ObjectMapper objectMapper;
    private final TaskAccessGuard taskAccessGuard;

    public PreDefenseReportQueryService(
            PreDefenseTaskMapper taskMapper,
            ResourceQueryService resourceQueryService,
            CleanSubsetExportService exportService,
            ObjectMapper objectMapper,
            TaskAccessGuard taskAccessGuard) {
        this.taskMapper = taskMapper;
        this.resourceQueryService = resourceQueryService;
        this.exportService = exportService;
        this.objectMapper = objectMapper;
        this.taskAccessGuard = taskAccessGuard;
    }

    public Map<String, Object> getReport(Long taskId) {
        PreDefenseTask task = taskAccessGuard.requireReadable(taskId);
        Dataset dataset = resourceQueryService.findDataset(task.getDatasetId());
        Algorithm algorithm = resourceQueryService.findAlgorithm(task.getAlgorithmId());
        Hyper hyper = task.getHyperId() == null ? null : resourceQueryService.findHyper(task.getHyperId());
        List<CleanSubsetExportTask> exports = exportService.findByTaskId(taskId);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("taskInfo", task);
        report.put("dataset", dataset);
        report.put("algorithm", algorithm);
        report.put("hyperParameter", hyper);
        report.put("executionSnapshot", parse(task.getExecutionSnapshot()));
        report.put("cleaningResult", parse(task.getCleaningResult()));
        report.put("cleanDatasetDownload", exportService.describe(task));
        report.put("cleanDatasetExports", exports);
        report.put("reportGeneratedAt", LocalDateTime.now().toString());
        return report;
    }

    private Object parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return objectMapper.convertValue(node, Object.class);
        } catch (Exception ignored) {
            return raw;
        }
    }
}
