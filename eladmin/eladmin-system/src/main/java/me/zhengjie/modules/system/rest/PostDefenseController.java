package me.zhengjie.modules.system.rest;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.annotation.Log;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.PostDefense;
import me.zhengjie.modules.system.domain.dto.PostDefenseQueryCriteria;
import me.zhengjie.modules.system.service.PostDefenseService;
import me.zhengjie.utils.PageResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

/**
 * 训练后防御任务控制器
 * @author ZJING
 * @date 2025-08-08
 */
@Slf4j
@Api(tags = "系统：训练后防御管理")
@RestController
@RequestMapping("/api/post-defense")
@RequiredArgsConstructor
public class PostDefenseController {

    private final PostDefenseService postDefenseService;
    private static final String ENTITY_NAME = "postDefense";

    @ApiOperation("导出训练后防御任务数据")
    @GetMapping(value = "/download")
    @PreAuthorize("@el.check('post_defense:list')")
    public void exportPostDefense(HttpServletResponse response, PostDefenseQueryCriteria criteria) throws IOException {
        postDefenseService.download(postDefenseService.queryAll(criteria), response);
    }

    @ApiOperation("查询训练后防御任务")
    @GetMapping
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<PageResult<PostDefense>> queryPostDefense(PostDefenseQueryCriteria criteria) {
        Page<Object> page = new Page<>(criteria.getPage(), criteria.getSize());
        return new ResponseEntity<>(postDefenseService.queryAll(criteria, page), HttpStatus.OK);
    }

    @ApiOperation("根据ID查询训练后防御任务")
    @GetMapping("/{taskId}")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<PostDefense> getPostDefense(@PathVariable Long taskId) {
        return new ResponseEntity<>(postDefenseService.findById(taskId), HttpStatus.OK);
    }

    @Log("新增训练后防御任务")
    @ApiOperation("新增训练后防御任务")
    @PostMapping
    @PreAuthorize("@el.check('post_defense:add')")
    public ResponseEntity<Object> createPostDefense(@Validated @RequestBody PostDefense resources) {
        if (resources.getTaskId() != null) {
            throw new BadRequestException("A new " + ENTITY_NAME + " cannot already have an ID");
        }
        postDefenseService.create(resources);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @Log("修改训练后防御任务")
    @ApiOperation("修改训练后防御任务")
    @PutMapping
    @PreAuthorize("@el.check('post_defense:edit')")
    public ResponseEntity<Object> updatePostDefense(@Validated(PostDefense.Update.class) @RequestBody PostDefense resources) {
        postDefenseService.update(resources);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Log("删除训练后防御任务")
    @ApiOperation("删除训练后防御任务")
    @DeleteMapping
    @PreAuthorize("@el.check('post_defense:del')")
    public ResponseEntity<Object> deletePostDefense(@RequestBody Set<Long> taskIds) {
        log.info("deletePostDefense called with task IDs: {}", taskIds);
        postDefenseService.delete(taskIds);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("启动训练后防御任务")
    @ApiOperation("启动训练后防御任务")
    @PostMapping("/start/{taskId}")
    @PreAuthorize("@el.check('post_defense:start')")
    public ResponseEntity<Object> startPostDefenseTask(@PathVariable Long taskId) {
        postDefenseService.startTask(taskId);
        return new ResponseEntity<>("任务启动成功", HttpStatus.OK);
    }

    @ApiOperation("获取任务进度")
    @GetMapping("/progress/{taskId}")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getTaskProgress(@PathVariable Long taskId) {
        Integer progress = postDefenseService.getTaskProgress(taskId);
        return new ResponseEntity<>(progress, HttpStatus.OK);
    }

    @Log("更新任务状态")
    @ApiOperation("更新任务状态")
    @PutMapping("/status")
    @PreAuthorize("@el.check('post_defense:edit')")
    public ResponseEntity<Object> updateTaskStatus(
            @RequestParam Long taskId,
            @RequestParam String status,
            @RequestParam(required = false, defaultValue = "0") Integer progress) {
        postDefenseService.updateTaskStatus(taskId, status, progress);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新防御结果")
    @ApiOperation("更新防御结果")
    @PutMapping("/defense-result")
    @PreAuthorize("@el.check('post_defense:edit')")
    public ResponseEntity<Object> updateDefenseResult(
            @RequestParam Long taskId,
            @RequestParam String defenseResult,
            @RequestParam(required = false) Long outputModelId,
            @RequestParam(required = false) String outputModelPath) {
        postDefenseService.updateDefenseResult(taskId, defenseResult, outputModelId, outputModelPath);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新性能指标")
    @ApiOperation("更新性能指标")
    @PutMapping("/performance-metrics")
    @PreAuthorize("@el.check('post_defense:edit')")
    public ResponseEntity<Object> updatePerformanceMetrics(
            @RequestParam Long taskId,
            @RequestBody String performanceMetrics) {
        postDefenseService.updatePerformanceMetrics(taskId, performanceMetrics);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新风险评估")
    @ApiOperation("更新风险评估")
    @PutMapping("/risk-assessment")
    @PreAuthorize("@el.check('post_defense:edit')")
    public ResponseEntity<Object> updateRiskAssessment(
            @RequestParam Long taskId,
            @RequestBody String riskAssessment) {
        postDefenseService.updateRiskAssessment(taskId, riskAssessment);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新检测报告")
    @ApiOperation("更新检测报告")
    @PutMapping("/detection-report")
    @PreAuthorize("@el.check('post_defense:edit')")
    public ResponseEntity<Object> updateDetectionReport(
            @RequestParam Long taskId,
            @RequestBody String detectionReport) {
        postDefenseService.updateDetectionReport(taskId, detectionReport);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("根据状态查询任务")
    @GetMapping("/status/{status}")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getTasksByStatus(@PathVariable String status) {
        return new ResponseEntity<>(postDefenseService.findByStatus(status), HttpStatus.OK);
    }

    @ApiOperation("根据任务名称查询")
    @GetMapping("/name/{taskName}")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<PostDefense> getTaskByName(@PathVariable String taskName) {
        PostDefense task = postDefenseService.findByTaskName(taskName);
        if (task == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(task, HttpStatus.OK);
    }

    @ApiOperation("根据输入模型ID查询任务")
    @GetMapping("/input-model/{inputModelId}")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getTasksByInputModel(@PathVariable Long inputModelId) {
        return new ResponseEntity<>(postDefenseService.findByInputModelId(inputModelId), HttpStatus.OK);
    }

    @ApiOperation("根据算法ID查询任务")
    @GetMapping("/algorithm/{algorithmId}")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getTasksByAlgorithm(@PathVariable Long algorithmId) {
        return new ResponseEntity<>(postDefenseService.findByAlgorithmId(algorithmId), HttpStatus.OK);
    }

    @ApiOperation("批量获取任务进度")
    @PostMapping("/progress/batch")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getBatchTaskProgress(@RequestBody Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BadRequestException("任务ID列表不能为空");
        }
        
        java.util.Map<Long, Integer> progressMap = new java.util.HashMap<>();
        for (Long taskId : taskIds) {
            try {
                Integer progress = postDefenseService.getTaskProgress(taskId);
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
    @PreAuthorize("@el.check('post_defense:start')")
    public ResponseEntity<Object> startBatchTasks(@RequestBody Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BadRequestException("任务ID列表不能为空");
        }
        
        java.util.Map<String, Object> result = postDefenseService.startBatchTasks(taskIds);
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("获取任务统计信息")
    @GetMapping("/statistics")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getTaskStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        
        // 按状态统计任务数量
        stats.put("pendingCount", postDefenseService.findByStatus("pending").size());
        stats.put("runningCount", postDefenseService.findByStatus("running").size());
        stats.put("successCount", postDefenseService.findByStatus("success").size());
        stats.put("failedCount", postDefenseService.findByStatus("failed").size());
        
        // 总任务数
        PostDefenseQueryCriteria criteria = new PostDefenseQueryCriteria();
        stats.put("totalCount", postDefenseService.queryAll(criteria).size());
        
        return new ResponseEntity<>(stats, HttpStatus.OK);
    }

    @ApiOperation("获取防御结果统计")
    @GetMapping("/defense-result-statistics")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getDefenseResultStatistics() {
        return new ResponseEntity<>(postDefenseService.getDefenseResultStatistics(), HttpStatus.OK);
    }

    @ApiOperation("获取算法类型统计")
    @GetMapping("/algorithm-type-statistics")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getAlgorithmTypeStatistics() {
        return new ResponseEntity<>(postDefenseService.getAlgorithmTypeStatistics(), HttpStatus.OK);
    }

    @ApiOperation("获取输出模型")
    @GetMapping("/output-model/{taskId}")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getOutputModel(@PathVariable Long taskId) {
        PostDefense task = postDefenseService.findById(taskId);
        
        if (task.getOutputModelId() == null && task.getOutputModelPath() == null) {
            throw new BadRequestException("该任务尚未生成输出模型");
        }
        
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("taskId", taskId);
        result.put("taskName", task.getTaskName());
        result.put("defenseResult", task.getDefenseResult());
        result.put("outputModelId", task.getOutputModelId());
        result.put("outputModelPath", task.getOutputModelPath());
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @ApiOperation("获取完整防御报告")
    @GetMapping("/report/{taskId}")
    @PreAuthorize("@el.check('post_defense:list')")
    public ResponseEntity<Object> getDefenseReport(@PathVariable Long taskId) {
        PostDefense task = postDefenseService.findById(taskId);
        
        java.util.Map<String, Object> report = new java.util.HashMap<>();
        report.put("taskInfo", task);
        report.put("performanceMetrics", task.getPerformanceMetrics());
        report.put("riskAssessment", task.getRiskAssessment());
        report.put("detectionReport", task.getDetectionReport());
        
        return new ResponseEntity<>(report, HttpStatus.OK);
    }
}