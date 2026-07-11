package me.zhengjie.modules.system.rest;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.annotation.Log;
import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.DuringDefense;
import me.zhengjie.modules.system.domain.dto.DuringDefenseQueryCriteria;
import me.zhengjie.modules.system.service.DuringDefenseService;
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
 * 训练中防御任务控制器
 * @author ZJING
 * @date 2025-08-04
 */
@Slf4j
@Api(tags = "系统：训练中防御管理")
@RestController
@RequestMapping("/api/during-defense")
@RequiredArgsConstructor
public class DuringDefenseController {

    private final DuringDefenseService duringDefenseService;
    private static final String ENTITY_NAME = "duringDefense";

    @ApiOperation("导出训练中防御任务数据")
    @GetMapping(value = "/download")
    @PreAuthorize("@el.check('during_defense:list')")
    public void exportDuringDefense(HttpServletResponse response, DuringDefenseQueryCriteria criteria) throws IOException {
        duringDefenseService.download(duringDefenseService.queryAll(criteria), response);
    }

    @ApiOperation("查询训练中防御任务")
    @GetMapping
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<PageResult<DuringDefense>> queryDuringDefense(DuringDefenseQueryCriteria criteria) {
        Page<Object> page = new Page<>(criteria.getPage(), criteria.getSize());
        return new ResponseEntity<>(duringDefenseService.queryAll(criteria, page), HttpStatus.OK);
    }

    @ApiOperation("根据ID查询训练中防御任务")
    @GetMapping("/{taskId}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<DuringDefense> getDuringDefense(@PathVariable Long taskId) {
        return new ResponseEntity<>(duringDefenseService.findById(taskId), HttpStatus.OK);
    }

    @Log("新增训练中防御任务")
    @ApiOperation("新增训练中防御任务")
    @PostMapping
    @PreAuthorize("@el.check('during_defense:add')")
    public ResponseEntity<Object> createDuringDefense(@Validated @RequestBody DuringDefense resources) {
        if (resources.getTaskId() != null) {
            throw new BadRequestException("A new " + ENTITY_NAME + " cannot already have an ID");
        }
        duringDefenseService.create(resources);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @Log("修改训练中防御任务")
    @ApiOperation("修改训练中防御任务")
    @PutMapping
    @PreAuthorize("@el.check('during_defense:edit')")
    public ResponseEntity<Object> updateDuringDefense(@Validated(DuringDefense.Update.class) @RequestBody DuringDefense resources) {
        duringDefenseService.update(resources);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }

    @Log("删除训练中防御任务")
    @ApiOperation("删除训练中防御任务")
    @DeleteMapping
    @PreAuthorize("@el.check('during_defense:del')")
    public ResponseEntity<Object> deleteDuringDefense(@RequestBody Set<Long> taskIds) {
        log.info("deleteDuringDefense called with task IDs: {}", taskIds);
        duringDefenseService.delete(taskIds);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("启动训练中防御任务")
    @ApiOperation("启动训练中防御任务")
    @PostMapping("/start/{taskId}")
    @PreAuthorize("@el.check('during_defense:start')")
    public ResponseEntity<Object> startDuringDefenseTask(@PathVariable Long taskId) {
        duringDefenseService.startTask(taskId);
        return new ResponseEntity<>("任务启动成功", HttpStatus.OK);
    }

    @ApiOperation("获取任务进度")
    @GetMapping("/progress/{taskId}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> getTaskProgress(@PathVariable Long taskId) {
        Integer progress = duringDefenseService.getTaskProgress(taskId);
        return new ResponseEntity<>(progress, HttpStatus.OK);
    }

    @Log("更新任务状态")
    @ApiOperation("更新任务状态")
    @PutMapping("/status")
    @PreAuthorize("@el.check('during_defense:edit')")
    public ResponseEntity<Object> updateTaskStatus(
            @RequestParam Long taskId,
            @RequestParam String status,
            @RequestParam(required = false, defaultValue = "0") Integer progress) {
        duringDefenseService.updateTaskStatus(taskId, status, progress);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @Log("更新训练结果")
    @ApiOperation("更新训练结果")
    @PutMapping("/result")
    @PreAuthorize("@el.check('during_defense:edit')")
    public ResponseEntity<Object> updateTrainingResult(
            @RequestParam Long taskId,
            @RequestParam(required = false) Long outputModelId,
            @RequestParam(required = false) String trainingLog) {
        duringDefenseService.updateTrainingResult(taskId, outputModelId, trainingLog);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("根据状态查询任务")
    @GetMapping("/status/{status}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> getTasksByStatus(@PathVariable String status) {
        return new ResponseEntity<>(duringDefenseService.findByStatus(status), HttpStatus.OK);
    }

    @ApiOperation("根据任务名称查询")
    @GetMapping("/name/{taskName}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<DuringDefense> getTaskByName(@PathVariable String taskName) {
        DuringDefense task = duringDefenseService.findByTaskName(taskName);
        if (task == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(task, HttpStatus.OK);
    }

    @ApiOperation("根据数据集ID查询任务")
    @GetMapping("/dataset/{datasetId}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> getTasksByDataset(@PathVariable Long datasetId) {
        return new ResponseEntity<>(duringDefenseService.findByDatasetId(datasetId), HttpStatus.OK);
    }

    @ApiOperation("根据输入模型ID查询任务")
    @GetMapping("/input-model/{inputModelId}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> getTasksByInputModel(@PathVariable Long inputModelId) {
        return new ResponseEntity<>(duringDefenseService.findByInputModelId(inputModelId), HttpStatus.OK);
    }

    @ApiOperation("根据算法ID查询任务")
    @GetMapping("/algorithm/{algorithmId}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> getTasksByAlgorithm(@PathVariable Long algorithmId) {
        return new ResponseEntity<>(duringDefenseService.findByAlgorithmId(algorithmId), HttpStatus.OK);
    }

    @ApiOperation("批量获取任务进度")
    @PostMapping("/progress/batch")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> getBatchTaskProgress(@RequestBody Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BadRequestException("任务ID列表不能为空");
        }
        
        java.util.Map<Long, Integer> progressMap = new java.util.HashMap<>();
        for (Long taskId : taskIds) {
            try {
                Integer progress = duringDefenseService.getTaskProgress(taskId);
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
    @PreAuthorize("@el.check('during_defense:start')")
    public ResponseEntity<Object> startBatchTasks(@RequestBody Set<Long> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            throw new BadRequestException("任务ID列表不能为空");
        }
        
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        java.util.List<Long> successIds = new java.util.ArrayList<>();
        java.util.List<String> failureReasons = new java.util.ArrayList<>();
        
        for (Long taskId : taskIds) {
            try {
                duringDefenseService.startTask(taskId);
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
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> getTaskStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        
        // 按状态统计任务数量
        stats.put("pendingCount", duringDefenseService.findByStatus("pending").size());
        stats.put("runningCount", duringDefenseService.findByStatus("running").size());
        stats.put("successCount", duringDefenseService.findByStatus("success").size());
        stats.put("failedCount", duringDefenseService.findByStatus("failed").size());
        
        // 总任务数
        DuringDefenseQueryCriteria criteria = new DuringDefenseQueryCriteria();
        stats.put("totalCount", duringDefenseService.queryAll(criteria).size());
        
        return new ResponseEntity<>(stats, HttpStatus.OK);
    }

    @ApiOperation("获取训练日志")
    @GetMapping("/training-log/{taskId}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> getTrainingLog(@PathVariable Long taskId) {
        DuringDefense task = duringDefenseService.findById(taskId);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("taskId", taskId);
        result.put("taskName", task.getTaskName());
        result.put("trainingLog", task.getTrainingLog());
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    @Log("更新训练日志")
    @ApiOperation("更新训练日志")
    @PutMapping("/training-log")
    @PreAuthorize("@el.check('during_defense:edit')")
    public ResponseEntity<Object> updateTrainingLog(
            @RequestParam Long taskId,
            @RequestBody String trainingLog) {
        duringDefenseService.updateTrainingLog(taskId, trainingLog);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @ApiOperation("下载训练后模型")
    @GetMapping("/download-model/{taskId}")
    @PreAuthorize("@el.check('during_defense:list')")
    public ResponseEntity<Object> downloadTrainedModel(@PathVariable Long taskId) {
        DuringDefense task = duringDefenseService.findById(taskId);
        
        if (task.getOutputModelId() == null) {
            throw new BadRequestException("该任务尚未生成训练后模型");
        }
        
        // TODO: 这里应该调用模型下载服务
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("taskId", taskId);
        result.put("outputModelId", task.getOutputModelId());
        result.put("message", "模型下载功能需要与模型管理服务集成");
        
        return new ResponseEntity<>(result, HttpStatus.OK);
    }
}