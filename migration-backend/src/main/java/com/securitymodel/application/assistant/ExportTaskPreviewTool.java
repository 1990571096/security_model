package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.application.export.CleanSubsetExportService;
import com.securitymodel.application.task.TaskAccessGuard;
import com.securitymodel.domain.task.PreDefenseTask;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Export preview validates the saved report and ratio; it does not queue a ZIP job. */
@Component
public class ExportTaskPreviewTool implements AssistantTool {

    private final TaskAccessGuard taskAccessGuard;
    private final CleanSubsetExportService exportService;
    private final ObjectMapper objectMapper;

    public ExportTaskPreviewTool(TaskAccessGuard taskAccessGuard, CleanSubsetExportService exportService,
                                 ObjectMapper objectMapper) {
        this.taskAccessGuard = taskAccessGuard;
        this.exportService = exportService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        ObjectNode schema = AssistantSchemas.object();
        AssistantSchemas.integer(schema, "taskId", "当前用户自己的成功任务 ID", true);
        AssistantSchemas.integer(schema, "isolationRatio", "可选隔离比例；不传时使用报告默认比例", false);
        return new AssistantToolDescriptor("preview_export_task",
                "预览清洗子集导出；只校验报告和比例，不创建导出任务、不写 ZIP", true, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("taskId", "isolationRatio"));
        Long taskId = AssistantArgs.requireLong(arguments, "taskId");
        Long requested = AssistantArgs.optionalLong(arguments, "isolationRatio");
        Integer requestedRatio = requested == null ? null : requested.intValue();
        PreDefenseTask task = taskAccessGuard.requireOwned(taskId);
        if (!"success".equals(task.getStatus())) {
            throw new AssistantException("ASSISTANT_TASK_STATE_INVALID",
                    org.springframework.http.HttpStatus.CONFLICT, "只有 success 任务可以预览导出");
        }
        Map<String, Object> described = exportService.describe(task);
        if (!Boolean.TRUE.equals(described.get("available"))) {
            throw new AssistantException("ASSISTANT_EXPORT_UNAVAILABLE",
                    org.springframework.http.HttpStatus.CONFLICT, "当前任务没有可导出的 LDD 清洗子集");
        }
        @SuppressWarnings("unchecked")
        List<Integer> ratios = (List<Integer>) described.getOrDefault("isolationRatios", List.of());
        Integer ratio = requestedRatio == null ? (Integer) described.get("defaultIsolationRatio") : requestedRatio;
        if (ratio == null || !ratios.contains(ratio)) {
            throw new AssistantException("ASSISTANT_EXPORT_RATIO_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "隔离比例不在当前报告可选范围内");
        }

        ObjectNode request = objectMapper.createObjectNode();
        request.put("taskId", task.getTaskId());
        request.put("isolationRatio", ratio);
        ObjectNode data = request.deepCopy();
        data.put("operation", "export");
        data.put("taskName", task.getTaskName());
        data.set("availableRatios", objectMapper.valueToTree(ratios));
        data.put("warning", "确认后会排队生成 ZIP；不会读取或修改其他用户的数据。");
        String summary = "导出预览：任务 #" + taskId + " 可按 " + ratio + "% 隔离比例导出；确认前不会创建导出任务。";
        return new AssistantToolResult(summary, data, "export", request, task.getTaskId(),
                task.getCurrentRunId(),
                AssistantContextHasher.taskState("export", task, String.valueOf(ratio)));
    }
}
