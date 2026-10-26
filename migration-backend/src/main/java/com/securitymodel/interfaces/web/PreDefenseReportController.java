package com.securitymodel.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.report.PreDefenseLogQueryService;
import com.securitymodel.application.report.PreDefenseReportQueryService;
import com.securitymodel.application.task.ExecutionFingerprintService;
import com.securitymodel.domain.export.CleanSubsetExportTask;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@RestController
@RequestMapping("/api/pre-defense")
public class PreDefenseReportController {

    private final PreDefenseReportQueryService reportService;
    private final PreDefenseLogQueryService logService;
    private final CleanSubsetExportService exportService;
    private final PreDefenseTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final ExecutionFingerprintService fingerprintService;

    public PreDefenseReportController(
            PreDefenseReportQueryService reportService,
            PreDefenseLogQueryService logService,
            CleanSubsetExportService exportService,
            PreDefenseTaskMapper taskMapper,
            ObjectMapper objectMapper,
            ExecutionFingerprintService fingerprintService) {
        this.reportService = reportService;
        this.logService = logService;
        this.exportService = exportService;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.fingerprintService = fingerprintService;
    }

    @GetMapping("/report/{taskId}")
    public Map<String, Object> report(@PathVariable Long taskId) {
        return reportService.getReport(taskId);
    }

    @GetMapping("/logs/{taskId}")
    public Map<String, Object> logs(@PathVariable Long taskId,
                                    @RequestParam(required = false) String offsets) {
        return logService.getLogs(taskId, parseOffsets(offsets));
    }

    @PostMapping("/cleaned-dataset/exports/{taskId}")
    public ResponseEntity<CleanSubsetExportTask> submitExport(
            @PathVariable Long taskId,
            @RequestParam(required = false) Integer isolationRatio) {
        return ResponseEntity.accepted().body(exportService.submit(taskId, isolationRatio));
    }

    @GetMapping("/cleaned-dataset/exports/{exportId}")
    public CleanSubsetExportTask export(@PathVariable Long exportId) {
        return exportService.findRequired(exportId);
    }

    @GetMapping("/cleaned-dataset/exports/task/{taskId}")
    public List<CleanSubsetExportTask> exports(@PathVariable Long taskId) {
        return exportService.findByTaskId(taskId);
    }

    @GetMapping("/cleaned-dataset/exports/{exportId}/download")
    public ResponseEntity<Resource> download(@PathVariable Long exportId) throws IOException {
        CleanSubsetExportTask task = exportService.findRequired(exportId);
        Path path = exportService.resolveDownloadFile(exportId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + exportService.buildDownloadFileName(task) + "\"")
                .contentLength(Files.size(path))
                .body(new org.springframework.core.io.FileSystemResource(path));
    }

    @GetMapping("/artifact-preview/{taskId}")
    public ResponseEntity<Resource> artifact(
            @PathVariable Long taskId,
            @RequestParam String artifactPath) throws IOException {
        PreDefenseTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        JsonNode result = objectMapper.readTree(task.getCleaningResult());
        Path root = Paths.get(result.path("artifacts").path("path").asText()).toAbsolutePath().normalize();
        Path target = Paths.get(artifactPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(target) || !target.toRealPath().startsWith(root.toRealPath())
                || !target.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new IllegalArgumentException("请求的产物文件不可访问");
        }
        MediaType mediaType = Optional.ofNullable(Files.probeContentType(target))
                .map(MediaType::parseMediaType).orElse(MediaType.IMAGE_PNG);
        return ResponseEntity.ok().contentType(mediaType)
                .body(new org.springframework.core.io.FileSystemResource(target.toRealPath()));
    }

    private Map<String, Long> parseOffsets(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isObject()) {
                throw new IllegalArgumentException();
            }
            Map<String, Long> offsets = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                long value = entry.getValue().asLong(-1);
                if (value < 0) {
                    throw new IllegalArgumentException();
                }
                offsets.put(entry.getKey(), value);
            });
            return offsets;
        } catch (Exception exception) {
            throw new IllegalArgumentException("日志游标格式无效");
        }
    }
}
