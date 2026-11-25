package com.securitymodel.application.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securitymodel.application.knowledge.KnowledgeSanitizer;
import com.securitymodel.application.task.TaskProgressQueryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
class MyTaskListTool implements AssistantTool {

    static final String NAME = "list_my_tasks";
    static final int LIMIT = 5;
    static final Set<String> STATUSES = Set.of("pending", "running", "failed", "success", "canceled");
    private final TaskProgressQueryService queries;
    private final ObjectMapper mapper;

    MyTaskListTool(TaskProgressQueryService queries, ObjectMapper mapper) {
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    public AssistantToolDescriptor descriptor() {
        var schema = AssistantSchemas.object();
        for (String field : Set.of("status", "excludeStatus")) {
            schema.withObject("/properties").putObject(field).put("type", "string")
                    .putArray("enum").add("pending").add("running").add("failed").add("success").add("canceled");
        }
        return new AssistantToolDescriptor(NAME, "查看本人最近的任务，可包含或排除一个状态。", false, schema);
    }

    @Override
    public AssistantToolResult execute(JsonNode arguments) {
        AssistantArgs.rejectUnknown(arguments, Set.of("status", "excludeStatus"));
        String status = AssistantArgs.optionalText(arguments, "status", 16);
        String exclude = AssistantArgs.optionalText(arguments, "excludeStatus", 16);
        if ((status != null && !STATUSES.contains(status)) || (exclude != null && !STATUSES.contains(exclude))
                || (status != null && exclude != null)) {
            throw new AssistantException("ASSISTANT_TOOL_ARGUMENTS_INVALID", HttpStatus.BAD_REQUEST,
                    "只能包含或排除一个有效的任务状态");
        }
        var page = queries.findMinePage(status, exclude, LIMIT);
        var data = mapper.createObjectNode().put("total", page.total());
        if (status != null) {
            data.put("status", status);
        }
        if (exclude != null) {
            data.put("excludeStatus", exclude);
        }
        var items = data.putArray("tasks");
        for (var task : page.records()) {
            items.addObject()
                    .put("taskId", task.taskId().toString())
                    .put("taskName", KnowledgeSanitizer.forModel(task.name(), 65))
                    .put("status", task.status())
                    .put("progress", task.progress())
                    .put("runId", task.currentRunId())
                    .put("attempt", task.currentAttempt());
        }
        return new AssistantToolResult("匹配 " + page.total() + " 条本人任务，返回 " + items.size() + " 条。",
                data, null, null, null, null, null);
    }
}
