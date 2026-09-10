package me.zhengjie.modules.system.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import me.zhengjie.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.zhengjie.modules.system.domain.dto.TaskProgressDto;
import me.zhengjie.modules.system.service.TaskProgressService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 任务进度查询控制器
 * @author ZJING
 * @date 2025-08-09
 */
@Slf4j
@Api(tags = "系统：任务进度查询")
@RestController
@RequestMapping("/api/task-progress")
@RequiredArgsConstructor
public class TaskProgressController {

    private static final int MAX_BATCH_TASKS = 100;
    private static final String PRE_DEFENSE_TASK_TYPE = "pre_defense";
    private final TaskProgressService taskProgressService;

    @ApiOperation("获取所有运行中的任务")
    @GetMapping("/running")
    @PreAuthorize("@el.check('task_progress:list')")
    public ResponseEntity<List<TaskProgressDto>> getRunningTasks() {
        List<TaskProgressDto> runningTasks = taskProgressService.getRunningTasks();
        return new ResponseEntity<>(runningTasks, HttpStatus.OK);
    }

    @ApiOperation("获取所有任务（支持状态筛选）")
    @GetMapping
    @PreAuthorize("@el.check('task_progress:list')")
    public ResponseEntity<List<TaskProgressDto>> getAllTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String blurry) {
        List<TaskProgressDto> tasks = taskProgressService.getAllTasks(status, taskType, blurry);
        return new ResponseEntity<>(tasks, HttpStatus.OK);
    }

    @ApiOperation("根据任务类型和ID获取任务详情")
    @GetMapping("/{taskType}/{taskId}")
    @PreAuthorize("@el.check('task_progress:list')")
    public ResponseEntity<TaskProgressDto> getTaskDetail(
            @PathVariable String taskType,
            @PathVariable Long taskId) {
        TaskProgressDto task = taskProgressService.getTaskDetail(taskType, taskId);
        return new ResponseEntity<>(task, HttpStatus.OK);
    }

    @ApiOperation("获取任务统计信息")
    @GetMapping("/statistics")
    @PreAuthorize("@el.check('task_progress:list')")
    public ResponseEntity<Map<String, Object>> getTaskStatistics() {
        Map<String, Object> statistics = taskProgressService.getTaskStatistics();
        return new ResponseEntity<>(statistics, HttpStatus.OK);
    }

    @ApiOperation("获取任务执行线程池状态")
    @GetMapping("/executor-status")
    @PreAuthorize("@el.check('task_progress:list')")
    public ResponseEntity<Map<String, Object>> getExecutorStatus() {
        return new ResponseEntity<>(taskProgressService.getExecutorStatus(), HttpStatus.OK);
    }

    @ApiOperation("批量获取任务进度")
    @PostMapping("/progress/batch")
    @PreAuthorize("@el.check('task_progress:list')")
    public ResponseEntity<Map<String, Object>> getBatchTaskProgress(@RequestBody List<Map<String, Object>> taskInfos) {
        // taskInfos格式: [{"taskType": "pre_defense", "taskId": 1}, ...]
        validateBatchTaskInfos(taskInfos);
        Map<String, Object> progressMap = taskProgressService.getBatchTaskProgress(taskInfos);
        return new ResponseEntity<>(progressMap, HttpStatus.OK);
    }

    @ApiOperation("刷新任务状态")
    @PostMapping("/refresh")
    @PreAuthorize("@el.check('task_progress:list')")
    public ResponseEntity<Object> refreshTaskStatus() {
        taskProgressService.refreshTaskStatus();
        return new ResponseEntity<>("任务状态刷新成功", HttpStatus.OK);
    }

    private void validateBatchTaskInfos(List<Map<String, Object>> taskInfos) {
        if (taskInfos == null || taskInfos.isEmpty()) {
            throw new BadRequestException("任务信息列表不能为空");
        }
        if (taskInfos.size() > MAX_BATCH_TASKS) {
            throw new BadRequestException("单次最多处理 " + MAX_BATCH_TASKS + " 个任务");
        }

        for (Map<String, Object> taskInfo : taskInfos) {
            if (taskInfo == null) {
                throw new BadRequestException("任务信息不能包含空值");
            }
            Object taskType = taskInfo.get("taskType");
            if (!PRE_DEFENSE_TASK_TYPE.equals(taskType)) {
                throw new BadRequestException("当前平台仅支持 LDD 训练前清洗任务");
            }
            Object taskId = taskInfo.get("taskId");
            if (taskId == null) {
                throw new BadRequestException("任务ID不能为空");
            }
            try {
                if (Long.parseLong(taskId.toString()) <= 0) {
                    throw new NumberFormatException("non-positive task ID");
                }
            } catch (NumberFormatException e) {
                throw new BadRequestException("任务ID必须是正整数");
            }
        }
    }
}
