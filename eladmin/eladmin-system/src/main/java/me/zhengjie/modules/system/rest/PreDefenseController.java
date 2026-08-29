// ===========================
// Controller - PreDefenseController.java
// ===========================
package me.zhengjie.modules.system.rest;

import cn.hutool.core.util.ObjectUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.json.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.annotation.Log;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.domain.dto.PreDefenseQueryCriteria;
import me.zhengjie.modules.system.service.AlgorithmService;
import me.zhengjie.modules.system.service.CleanSubsetExportService;
import me.zhengjie.modules.system.service.DatasetService;
import me.zhengjie.modules.system.service.HyperService;
import me.zhengjie.modules.system.service.PreDefenseService;
import me.zhengjie.utils.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 训练前防御任务控制器
 * @author ZJING
 * @date 2025-07-30
 */
@Slf4j
@Api(tags = "系统：训练前防御管理")
@RestController
@RequestMapping("/api/pre-defense")
@RequiredArgsConstructor
public class PreDefenseController {

    private final PreDefenseService preDefenseService;
    private final CleanSubsetExportService cleanSubsetExportService;
    private final DatasetService datasetService;
    private final AlgorithmService algorithmService;
    private final HyperService hyperService;
    private static final String ENTITY_NAME = "preDefense";

    @ApiOperation("导出训练前防御任务数据")
    @GetMapping(value = "/download")
    @PreAuthorize("@el.check('pre_defense:list')")
    public void exportPreDefense(HttpServletResponse response, PreDefenseQueryCriteria criteria) throws IOException {
        preDefenseService.download(preDefenseService.queryAll(criteria), response);
    }

    @ApiOperation("查询训练前防御任务")
    @GetMapping
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<PageResult<PreDefense>> queryPreDefense(PreDefenseQueryCriteria criteria) {
        Page<Object> page = new Page<>(criteria.getPage(), criteria.getSize());
        return new ResponseEntity<>(preDefenseService.queryAll(criteria, page), HttpStatus.OK);
    }

    @ApiOperation("根据ID查询训练前防御任务")
    @GetMapping("/{taskId}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<PreDefense> getPreDefense(@PathVariable Long taskId) {
        return new ResponseEntity<>(preDefenseService.findById(taskId), HttpStatus.OK);
    }

    @Log("新增训练前防御任务")
    @ApiOperation("新增训练前防御任务")
    @PostMapping
    @PreAuthorize("@el.check('pre_defense:add')")
    public ResponseEntity<Object> createPreDefense(@Validated @RequestBody PreDefense resources) {
        if (resources.getTaskId() != null) {
            throw new BadRequestException("A new " + ENTITY_NAME + " cannot already have an ID");
        }
        preDefenseService.create(resources);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @Log("修改训练前防御任务")
    @ApiOperation("修改训练前防御任务")
    @PutMapping
    @PreAuthorize("@el.check('pre_defense:edit')")
    public ResponseEntity<Object> updatePreDefense(@Validated(PreDefense.Update.class) @RequestBody PreDefense resources) {
        preDefenseService.update(resources);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Log("删除训练前防御任务")
    @ApiOperation("删除训练前防御任务")
    @DeleteMapping
    @PreAuthorize("@el.check('pre_defense:del')")
    public ResponseEntity<Object> deletePreDefense(@RequestBody Set<Long> taskIds) {
        // 添加日志记录接收到的ID
        log.info("deletePreDefense called with task IDs: {}", taskIds);
        preDefenseService.delete(taskIds);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("启动训练前防御任务")
    @ApiOperation("启动训练前防御任务")
    @PostMapping("/start/{taskId}")
    @PreAuthorize("@el.check('pre_defense:start')")
    public ResponseEntity<Object> startPreDefenseTask(@PathVariable Long taskId) {
        preDefenseService.startTask(taskId);
        return new ResponseEntity<>("任务启动成功", HttpStatus.OK);
    }

    @Log("重试训练前防御任务")
    @ApiOperation("重试失败的训练前防御任务")
    @PostMapping("/retry/{taskId}")
    @PreAuthorize("@el.check('pre_defense:start')")
    public ResponseEntity<Object> retryPreDefenseTask(@PathVariable Long taskId) {
        preDefenseService.retryTask(taskId);
        return new ResponseEntity<>("任务已重新启动", HttpStatus.OK);
    }

    @ApiOperation("获取任务进度")
    @GetMapping("/progress/{taskId}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getTaskProgress(@PathVariable Long taskId) {
        Integer progress = preDefenseService.getTaskProgress(taskId);
        return new ResponseEntity<>(progress, HttpStatus.OK);
    }

    @Log("更新任务状态")
    @ApiOperation("更新任务状态")
    @PutMapping("/status")
    @PreAuthorize("@el.check('pre_defense:edit')")
    public ResponseEntity<Object> updateTaskStatus(
            @RequestParam Long taskId,
            @RequestParam String status,
            @RequestParam(required = false, defaultValue = "0") Integer progress) {
        preDefenseService.updateTaskStatus(taskId, status, progress);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新任务结果")
    @ApiOperation("更新任务结果")
    @PutMapping("/result")
    @PreAuthorize("@el.check('pre_defense:edit')")
    public ResponseEntity<Object> updateTaskResult(
            @RequestParam Long taskId,
            @RequestParam(required = false) Long outputDatasetId,
            @RequestParam(required = false) String cleaningResult) {
        preDefenseService.updateTaskResult(taskId, outputDatasetId, cleaningResult);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("获取清洗结果详情")
    @GetMapping("/cleaning-result/{taskId}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getCleaningResult(@PathVariable Long taskId) {
        PreDefense task = preDefenseService.findById(taskId);
        if (ObjectUtil.isEmpty(task.getCleaningResult())) {
            return new ResponseEntity<>("任务尚未完成或无清洗结果", HttpStatus.NOT_FOUND);
        }
        try {
            // 修复：使用hutool的JSONUtil进行JSON解析
            Object result = JSONUtil.parseObj(task.getCleaningResult());
            return new ResponseEntity<>(result, HttpStatus.OK);
        } catch (Exception e) {
            log.warn("解析清洗结果JSON失败，任务ID: {}, 错误: {}", taskId, e.getMessage());
            // 如果JSON解析失败，返回原始字符串
            return new ResponseEntity<>(task.getCleaningResult(), HttpStatus.OK);
        }
    }

    @ApiOperation("获取清洗统计信息")
    @GetMapping("/cleaning-statistics")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getCleaningStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();

        // 按状态统计清洗任务数量
        stats.put("pendingCount", preDefenseService.findByStatus("pending").size());
        stats.put("runningCount", preDefenseService.findByStatus("running").size());
        stats.put("successCount", preDefenseService.findByStatus("success").size());
        stats.put("failedCount", preDefenseService.findByStatus("failed").size());

        // 总任务数
        PreDefenseQueryCriteria criteria = new PreDefenseQueryCriteria();
        stats.put("totalCount", preDefenseService.queryAll(criteria).size());

        return new ResponseEntity<>(stats, HttpStatus.OK);
    }

    @ApiOperation("获取清洗效果统计")
    @GetMapping("/cleaning-effect-statistics")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getCleaningEffectStatistics() {
        return new ResponseEntity<>(preDefenseService.getCleaningEffectStatistics(), HttpStatus.OK);
    }

    @ApiOperation("获取算法使用统计")
    @GetMapping("/algorithm-usage-statistics")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getAlgorithmUsageStatistics() {
        return new ResponseEntity<>(preDefenseService.getAlgorithmUsageStatistics(), HttpStatus.OK);
    }

    @ApiOperation("下载清洗后数据集")
    @GetMapping("/download-cleaned-dataset/{taskId}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Resource> downloadCleanedDataset(
            @PathVariable Long taskId,
            @RequestParam(required = false) Integer isolationRatio) throws IOException {
        PreDefense task = preDefenseService.findById(taskId);
        Path zipFile = cleanSubsetExportService.export(task, isolationRatio);
        String fileName = cleanSubsetExportService.buildDownloadFileName(task, isolationRatio);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentLength(Files.size(zipFile))
                .body(new FileSystemResource(zipFile));
    }

    @ApiOperation("预览真实清洗任务结果图")
    @GetMapping("/artifact-preview/{taskId}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Resource> previewTaskArtifact(
            @PathVariable Long taskId,
            @RequestParam String artifactPath) throws IOException {
        PreDefense task = preDefenseService.findById(taskId);
        if (ObjectUtil.isEmpty(task.getCleaningResult())) {
            throw new BadRequestException("该任务没有可预览的执行结果");
        }

        JSONObject result = JSON.parseObject(task.getCleaningResult());
        JSONObject artifacts = result.getJSONObject("artifacts");
        if (artifacts == null || ObjectUtil.isEmpty(artifacts.getString("path"))) {
            throw new BadRequestException("该任务没有可访问的产物目录");
        }

        Path artifactRoot = Paths.get(artifacts.getString("path")).toAbsolutePath().normalize();
        Path target = Paths.get(artifactPath).toAbsolutePath().normalize();
        if (!target.startsWith(artifactRoot) || !Files.isRegularFile(target)
                || !target.getFileName().toString().toLowerCase().endsWith(".png")) {
            throw new BadRequestException("请求的产物文件不可访问");
        }

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(Files.probeContentType(target));
        } catch (Exception ignored) {
            mediaType = MediaType.IMAGE_PNG;
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(new FileSystemResource(target));
    }

    @ApiOperation("获取完整清洗报告")
    @GetMapping("/report/{taskId}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getCleaningReport(@PathVariable Long taskId) {
        PreDefense task = preDefenseService.findById(taskId);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("taskInfo", task);
        report.put("dataset", datasetService.findById(task.getDatasetId()));
        report.put("algorithm", algorithmService.findById(task.getAlgorithmId()));
        report.put("hyperParameter", task.getHyperId() == null ? null : hyperService.findById(task.getHyperId()));
        report.put("executionSnapshot", parseJsonOrNull(task.getExecutionSnapshot()));
        report.put("cleaningResult", parseJsonOrNull(task.getCleaningResult()));
        report.put("cleanDatasetDownload", cleanSubsetExportService.describe(task));
        report.put("reportGeneratedAt", java.time.LocalDateTime.now().toString());

        return new ResponseEntity<>(report, HttpStatus.OK);
    }

    @ApiOperation("获取任务执行日志")
    @GetMapping("/logs/{taskId}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getExecutionLogs(@PathVariable Long taskId) {
        return new ResponseEntity<>(preDefenseService.getExecutionLogs(taskId), HttpStatus.OK);
    }

    private Object parseJsonOrNull(String raw) {
        if (ObjectUtil.isEmpty(raw)) {
            return null;
        }
        try {
            return JSON.parse(raw);
        } catch (Exception ignored) {
            return raw;
        }
    }

    @ApiOperation("根据状态查询任务")
    @GetMapping("/status/{status}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getTasksByStatus(@PathVariable String status) {
        return new ResponseEntity<>(preDefenseService.findByStatus(status), HttpStatus.OK);
    }

    @ApiOperation("根据任务名称查询")
    @GetMapping("/name/{taskName}")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<PreDefense> getTaskByName(@PathVariable String taskName) {
        PreDefense task = preDefenseService.findByTaskName(taskName);
        if (task == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(task, HttpStatus.OK);
    }

    @ApiOperation("批量获取任务进度")
    @PostMapping("/progress/batch")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getBatchTaskProgress(@RequestBody Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BadRequestException("任务ID列表不能为空");
        }
        
        java.util.Map<Long, Integer> progressMap = new java.util.HashMap<>();
        for (Long taskId : taskIds) {
            try {
                Integer progress = preDefenseService.getTaskProgress(taskId);
                progressMap.put(taskId, progress);
            } catch (Exception e) {
                log.warn("获取任务{}进度失败: {}", taskId, e.getMessage());
                progressMap.put(taskId, -1); // -1表示获取失败
            }
        }
        
        return new ResponseEntity<>(progressMap, HttpStatus.OK);
    }

    @Log("批量启动任务")
    @ApiOperation("批量启动任务")
    @PostMapping("/start/batch")
    @PreAuthorize("@el.check('pre_defense:start')")
    public ResponseEntity<Object> startBatchTasks(@RequestBody Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BadRequestException("任务ID列表不能为空");
        }
        
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        java.util.List<Long> successIds = new java.util.ArrayList<>();
        java.util.List<String> failureReasons = new java.util.ArrayList<>();
        
        for (Long taskId : taskIds) {
            try {
                preDefenseService.startTask(taskId);
                successIds.add(taskId);
            } catch (Exception e) {
                log.warn("启动任务{}失败: {}", taskId, e.getMessage());
                failureReasons.add("任务" + taskId + ": " + e.getMessage());
            }
        }
        
        result.put("successCount", successIds.size());
        result.put("successIds", successIds);
        result.put("failureCount", failureReasons.size());
        result.put("failureReasons", failureReasons);
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("获取任务统计信息")
    @GetMapping("/statistics")
    @PreAuthorize("@el.check('pre_defense:list')")
    public ResponseEntity<Object> getTaskStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        
        // 按状态统计任务数量
        stats.put("pendingCount", preDefenseService.findByStatus("pending").size());
        stats.put("runningCount", preDefenseService.findByStatus("running").size());
        stats.put("successCount", preDefenseService.findByStatus("success").size());
        stats.put("failedCount", preDefenseService.findByStatus("failed").size());
        
        // 总任务数
        PreDefenseQueryCriteria criteria = new PreDefenseQueryCriteria();
        stats.put("totalCount", preDefenseService.queryAll(criteria).size());
        
        return new ResponseEntity<>(stats, HttpStatus.OK);
    }
}
