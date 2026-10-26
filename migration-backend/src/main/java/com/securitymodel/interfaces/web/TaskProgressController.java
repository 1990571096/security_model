package com.securitymodel.interfaces.web;

import com.securitymodel.application.task.TaskProgressQueryService;
import com.securitymodel.application.task.TaskProgressView;
import com.securitymodel.application.task.TaskExecutionQuotaPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/task-progress")
public class TaskProgressController {

    private final TaskProgressQueryService taskProgressQueryService;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final TaskExecutionQuotaPolicy quotaPolicy;

    public TaskProgressController(
            TaskProgressQueryService taskProgressQueryService,
            @Qualifier("taskAsync") ThreadPoolTaskExecutor taskExecutor,
            TaskExecutionQuotaPolicy quotaPolicy) {
        this.taskProgressQueryService = taskProgressQueryService;
        this.taskExecutor = taskExecutor;
        this.quotaPolicy = quotaPolicy;
    }

    @GetMapping
    public List<TaskProgressView> tasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String taskType,
            @RequestParam(required = false) String blurry) {
        if (taskType != null && !taskType.isBlank() && !"pre_defense".equals(taskType)) {
            return List.of();
        }
        return taskProgressQueryService.findTasks(status, blurry);
    }

    @GetMapping("/page")
    public TaskProgressQueryService.TaskPage page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String blurry,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return taskProgressQueryService.findPage(status, blurry, page, size);
    }

    @GetMapping("/running")
    public List<TaskProgressView> running() {
        return taskProgressQueryService.findTasks("running", null);
    }

    @GetMapping("/{taskType}/{taskId}")
    public ResponseEntity<TaskProgressView> detail(
            @PathVariable String taskType,
            @PathVariable Long taskId) {
        if (!"pre_defense".equals(taskType)) {
            return ResponseEntity.notFound().build();
        }
        TaskProgressView view = taskProgressQueryService.findTask(taskId);
        return view == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(view);
    }

    @GetMapping("/statistics")
    public Map<String, Object> statistics() {
        return taskProgressQueryService.statistics();
    }

    @GetMapping("/executor-status")
    public Map<String, Object> executorStatus() {
        var executor = taskExecutor.getThreadPoolExecutor();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("mode", "spring-boot-python");
        status.put("poolSize", executor.getPoolSize());
        status.put("activeCount", executor.getActiveCount());
        status.put("corePoolSize", executor.getCorePoolSize());
        status.put("maxPoolSize", executor.getMaximumPoolSize());
        status.put("queueSize", executor.getQueue().size());
        status.put("queueCapacity", executor.getQueue().size() + executor.getQueue().remainingCapacity());
        status.put("completedTaskCount", executor.getCompletedTaskCount());
        status.put("taskCount", executor.getTaskCount());
        status.put("saturated", executor.getActiveCount() >= executor.getMaximumPoolSize()
                && executor.getQueue().remainingCapacity() == 0);
        status.put("acceptingTasks", !executor.isShutdown() && !executor.isTerminated());
        status.put("quota", quotaPolicy.status());
        status.put("runningTaskCount", status.get("activeCount"));
        status.put("maxRunningTasks", quotaPolicy.status().get("maxRunningTasks"));
        return status;
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return Map.of("message", "任务状态刷新成功", "source", "mysql");
    }
}
